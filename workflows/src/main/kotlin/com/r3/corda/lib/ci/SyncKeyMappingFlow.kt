package net.corda.confidential.identities

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.ci.RequestKeyInitiator
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.security.PublicKey

/**
 * This flow allows a node to share the [PublicKey] to [Party] mapping data of parties unknown to the counter-party. The
 * two constructors allow the the node to provide a transaction in which the confidential identities unknown to the counter-party
 * will be extracted and then registered in the identity service. Alternatively, the node can provide the confidential identities
 * it wishes to share with the counter-party directly. The initiating node sends the list of identities to the counter-party who
 * registers the identity mapping between a newly generated [PublicKey] and the [Party].
 *
 * The counter-party will request a new key mapping for each of the unresolved identities by calling [RequestKeyFlow] as
 * an inline flow.
 */
class SyncKeyMappingFlow(
        private val session: FlowSession,
        private val tx: WireTransaction?,
        private val identitiesToSync: List<AbstractParty>?) : FlowLogic<Unit>() {
    constructor(session: FlowSession, tx: WireTransaction) : this(session, tx, null)
    constructor(session: FlowSession, identitiesToSync: List<AbstractParty>) : this(session, null, identitiesToSync)

    companion object {
        object SYNCING_KEY_MAPPINGS : ProgressTracker.Step("Syncing key mappings.")

    }

    override val progressTracker = ProgressTracker(SYNCING_KEY_MAPPINGS)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = SYNCING_KEY_MAPPINGS
        val confidentialIdentities = mutableListOf<AbstractParty>()
        if (tx != null) {
            val ci = extractConfidentialIdentities()
            ci.forEach {
                confidentialIdentities.add(it)
            }
        } else {
            identitiesToSync?.forEach {
                confidentialIdentities.add(it)
            }
        }

        // Send confidential identities to the counter party and return a list of parties they wish to resolve
        val requestedIdentities = session.sendAndReceive<List<AbstractParty>>(confidentialIdentities).unwrap { req ->
            require(req.all { it in confidentialIdentities }) {
                "${session.counterparty} requested a confidential identity not part of transaction: ${tx?.id}"
            }
            req
        }

        val resolvedIds = requestedIdentities.map { serviceHub.identityService.wellKnownPartyFromAnonymous(it) }.filter { it != null }
        session.send(resolvedIds)
    }

    private fun extractConfidentialIdentities(): List<AbstractParty> {
        tx
                ?: throw IllegalArgumentException("A transaction must be provided if you wish to extract the confidential identities from it.")
        val inputStates: List<ContractState> = (tx.inputs.toSet()).mapNotNull {
            try {
                serviceHub.loadState(it).data
            } catch (e: TransactionResolutionException) {
                null
            }
        }
        val states: List<ContractState> = inputStates + tx.outputs.map { it.data }
        val identities: Set<AbstractParty> = states.flatMap(ContractState::participants).toSet()

        return identities
                .filter { serviceHub.networkMapCache.getNodesByLegalIdentityKey(it.owningKey).isEmpty() }
                .toList()
    }
}

class SyncKeyMappingFlowHandler(private val otherSession: FlowSession) : FlowLogic<Boolean>() {
    companion object {
        object RECEIVING_IDENTITIES : ProgressTracker.Step("Receiving confidential identities.")
        object RECEIVING_PARTIES : ProgressTracker.Step("Receiving potential party objects for unknown identities.")
        object NO_PARTIES_RECEIVED : ProgressTracker.Step("None of the requested unknown parties were resolved by the counter party. " +
                "Terminating the flow early.")

        object REQUESTING_PROOF_OF_ID : ProgressTracker.Step("Requesting a signed key to party mapping for the received parties to verify" +
                "the authenticity of the party.")

        object IDENTITIES_SYNCHRONISED : ProgressTracker.Step("Identities have finished synchronising.")
    }

    override val progressTracker: ProgressTracker = ProgressTracker(RECEIVING_IDENTITIES, RECEIVING_PARTIES, NO_PARTIES_RECEIVED, REQUESTING_PROOF_OF_ID, IDENTITIES_SYNCHRONISED)

    @Suspendable
    override fun call(): Boolean {
        progressTracker.currentStep = RECEIVING_IDENTITIES
        val allConfidentialIds = otherSession.receive<List<AbstractParty>>().unwrap { it }
        val unknownIdentities = allConfidentialIds.filter { serviceHub.identityService.wellKnownPartyFromAnonymous(it) == null }
        otherSession.send(unknownIdentities)
        progressTracker.currentStep = RECEIVING_PARTIES

        val parties = otherSession.receive<List<Party>>().unwrap { it }
        if (parties.isEmpty()) {
            progressTracker.currentStep = NO_PARTIES_RECEIVED
            return false
        }
        val mapConfidentialKeyToParty: Map<PublicKey, Party> = unknownIdentities.map { it.owningKey }.zip(parties).toMap()

        require(mapConfidentialKeyToParty.size == parties.size)

        progressTracker.currentStep = REQUESTING_PROOF_OF_ID

        mapConfidentialKeyToParty.forEach {
            subFlow(RequestKeyInitiator(it.value, it.key))
        }
        progressTracker.currentStep = IDENTITIES_SYNCHRONISED
        return true
    }
}