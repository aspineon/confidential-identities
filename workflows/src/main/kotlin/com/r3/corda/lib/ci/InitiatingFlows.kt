package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.transactions.WireTransaction
import java.security.PublicKey
import java.util.*

/**
 * Initiating version of [RequestKeyFlow].
 */
@StartableByRPC
@InitiatingFlow
class RequestKeyInitiator
private constructor(
        private val otherParty: Party,
        private val uuid: UUID?,
        private val key: PublicKey?
) : FlowLogic<SignedKeyForAccount>() {

    constructor(otherParty: Party, uuid: UUID) : this(otherParty, uuid, null)
    constructor(otherParty: Party, key: PublicKey) : this(otherParty, null, key)
    constructor(otherParty: Party) : this(otherParty, null, null)

    @Suspendable
    override fun call(): SignedKeyForAccount {
        return if (uuid != null) {
            subFlow(RequestKeyFlow(initiateFlow(otherParty), uuid))
        } else if (key != null){
            subFlow(RequestKeyFlow(initiateFlow(otherParty), key ))
        } else {
            subFlow(RequestKeyFlow(initiateFlow(otherParty)))
        }
    }
}

/**
 * Responder flow to [RequestKeyInitiator].
 */
@InitiatedBy(RequestKeyInitiator::class)
class RequestKeyResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ProvideKeyFlow(otherSession))
    }
}


/**
 * Initiating version of [SyncKeyMappingFlow].
 */
@InitiatingFlow
@StartableByRPC
class SyncKeyMappingInitiator
private constructor(
        private val otherParty: Party,
        private val tx: WireTransaction?,
        private val identitiesToSync: List<AbstractParty>?) : FlowLogic<Unit>() {
    constructor(otherParty: Party, tx: WireTransaction) : this(otherParty, tx, null)
    constructor(otherParty: Party, identitiesToSync: List<AbstractParty>) : this(otherParty, null, identitiesToSync)

    @Suspendable
    override fun call() {
        if (tx != null) {
            subFlow(SyncKeyMappingFlow(initiateFlow(otherParty), tx))
        } else {
            subFlow(SyncKeyMappingFlow(initiateFlow(otherParty), identitiesToSync
                    ?: throw IllegalArgumentException("A list of anonymous parties or a valid tx id must be provided to this flow.")))
        }
    }
}

/**
 * Responder flow to [SyncKeyMappingInitiator].
 */
@InitiatedBy(SyncKeyMappingInitiator::class)
class SyncKeyMappingResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(SyncKeyMappingFlowHandler(otherSession))
    }
}