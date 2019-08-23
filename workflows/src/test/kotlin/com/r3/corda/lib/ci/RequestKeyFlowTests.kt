package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.money.USD
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.node.ServiceHub
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.keys.PublicKeyHashToExternalId
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.CHARLIE_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.security.PublicKey
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestKeyFlowTests {

    private lateinit var mockNet: MockNetwork
    private lateinit var aliceNode: StartedMockNode
    private lateinit var bobNode: StartedMockNode
    private lateinit var charlieNode: StartedMockNode
    private lateinit var alice: Party
    private lateinit var bob: Party
    private lateinit var charlie: Party
    private lateinit var notary: Party

    @Before
    fun before() {
        mockNet = MockNetwork(
                MockNetworkParameters(
                        networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                        cordappsForAllNodes = listOf(
                                TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                                TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                                TestCordapp.findCordapp("com.r3.corda.lib.tokens.money"),
                                TestCordapp.findCordapp("com.r3.corda.lib.ci")
                        ),
                        threadPerNode = true
                )
        )
        aliceNode = mockNet.createPartyNode(ALICE_NAME)
        bobNode = mockNet.createPartyNode(BOB_NAME)
        charlieNode = mockNet.createPartyNode(CHARLIE_NAME)
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()
        charlie = charlieNode.info.singleIdentity()
        notary = mockNet.defaultNotaryIdentity

        mockNet.startNodes()

    }

    @After
    fun after() {
        mockNet.stopNodes()
    }

    @Test
    fun `request a new key`() {
        // Alice requests that bob generates a new key for an account
        val newKey = aliceNode.startFlow(RequestKey(bob)).let {
            it.getOrThrow()
        }.publicKey

        // Bob has the newly generated key as well as the owning key
        val bobKeys = bobNode.services.keyManagementService.keys
        val aliceKeys = aliceNode.services.keyManagementService.keys
        assertThat(bobKeys).hasSize(2)
        assertThat(aliceKeys).hasSize(1)

        assertThat(bobNode.services.keyManagementService.keys).contains(newKey)

        val resolvedBobParty = aliceNode.services.identityService.wellKnownPartyFromAnonymous(AnonymousParty(newKey))
        assertThat(resolvedBobParty).isEqualTo(bob)
    }

    @Test
    fun `request new key with a uuid provided`() {
        // Alice requests that bob generates a new key for an account
        val newKey = aliceNode.startFlow(RequestKeyForAccount(bob, UUID.randomUUID())).let {
            it.getOrThrow()
        }.publicKey

        // Bob has the newly generated key as well as the owning key
        val bobKeys = bobNode.services.keyManagementService.keys
        val aliceKeys = aliceNode.services.keyManagementService.keys
        assertThat(bobKeys).hasSize(2)
        assertThat(aliceKeys).hasSize(1)

        assertThat(bobNode.services.keyManagementService.keys).contains(newKey)

        val resolvedBobParty = aliceNode.services.identityService.wellKnownPartyFromAnonymous(AnonymousParty(newKey))
        assertThat(resolvedBobParty).isEqualTo(bob)
    }

    @Test
    fun `verify a known key with another party`() {
        // Charlie issues then pays some cash to a new confidential identity
        val anonymousParty = AnonymousParty(charlieNode.startFlow(RequestKey(alice)).let{
            it.getOrThrow()
        }.publicKey)

        val issueTx = charlieNode.startFlow(
                IssueTokens(listOf(1000 of USD issuedBy charlie heldBy AnonymousParty(anonymousParty.owningKey)))
        ).getOrThrow()
        val confidentialIdentity = issueTx.tx.outputs.map { it.data }.filterIsInstance<FungibleToken>().single().holder

        // Verify Bob cannot resolve the CI before we create a signed mapping of the CI key
        assertNull(bobNode.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })

        // Request a new key mapping for the CI
        bobNode.startFlow(VerifyAndAddKey(alice, confidentialIdentity.owningKey)).let {
            it.getOrThrow()
        }

        val expected = charlieNode.transaction {
            charlieNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        val actual = bobNode.transaction {
            bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        assertEquals(expected, actual)
    }

    //TODO fix lookup query
    @Ignore
    @Test
    fun `verify key can be looked up on both nodes involved in the key generation`() {
        val uuid = UUID.randomUUID()
        // Alice requests that bob generates a new key for an account
        val newKey = aliceNode.startFlow(RequestKeyForAccount(bob, uuid)).let {
            it.getOrThrow()
        }.publicKey

        aliceNode.transaction {
            assertThat(lookupPublicKey(uuid, aliceNode.services)).isEqualTo(newKey)
            assertThat(lookupPublicKey(uuid, bobNode.services)).isEqualTo(newKey)
        }
    }

    @Suspendable
    fun lookupPublicKey(uuid: UUID, serviceHub: ServiceHub): PublicKey? {
        val key = serviceHub.withEntityManager {
            val query = createQuery(
                    """
                        select $publicKeyHashToExternalId_publicKeyHash
                        from $publicKeyHashToExternalId
                        where $publicKeyHashToExternalId_externalId = :uuid
                    """,
                    PublicKey::class.java
            )
            query.setParameter("uuid", uuid)
            query.resultList
        }
        return key.singleOrNull()
    }

    /** Table names. */
    internal val publicKeyHashToExternalId = PublicKeyHashToExternalId::class.java.name

    /** Column names. */
    internal val publicKeyHashToExternalId_externalId = PublicKeyHashToExternalId::externalId.name
    internal val publicKeyHashToExternalId_publicKeyHash = PublicKeyHashToExternalId::publicKeyHash.name
}