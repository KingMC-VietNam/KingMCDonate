package net.kingmc.plugin.kingmcdonate.payment

import net.kingmc.plugin.kingmcdonate.config.PluginConfig
import net.kingmc.plugin.kingmcdonate.currency.CurrencyRegistry
import net.kingmc.plugin.kingmcdonate.currency.FakeCurrencyProvider
import net.kingmc.plugin.kingmcdonate.database.Database
import net.kingmc.plugin.kingmcdonate.database.dao.BankPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.CardPaymentDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerDao
import net.kingmc.plugin.kingmcdonate.database.dao.PlayerTotalsDao
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardPayload
import net.kingmc.plugin.kingmcdonate.payment.reward.RewardSink
import net.kingmc.plugin.kingmcdonate.util.PluginLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class ManualCreditServiceTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = PluginLogger(Logger.getAnonymousLogger(), debugMode = false)
    private lateinit var database: Database
    private lateinit var card: CardPaymentDao
    private lateinit var bank: BankPaymentDao
    private lateinit var totals: PlayerTotalsDao
    private lateinit var fakeCurrency: FakeCurrencyProvider
    private lateinit var enqueued: MutableList<UUID>
    private lateinit var success: DonationSuccessService
    private lateinit var service: ManualCreditService

    private fun config(pointRate: Double = 1.0): PluginConfig {
        val yaml = YamlConfiguration()
        yaml.loadFromString("server-id: \"node-a\"\nbank:\n  point-rate: $pointRate\n")
        return PluginConfig(yaml)
    }

    private fun buildService(pointRate: Double = 1.0): ManualCreditService {
        val cfg = config(pointRate)
        fakeCurrency = FakeCurrencyProvider(available = true)
        val currency = CurrencyRegistry(logger) { fakeCurrency }.apply { load(cfg.currency) }
        enqueued = mutableListOf()
        val sink = object : RewardSink {
            override fun enqueue(playerUuid: UUID, referenceCode: String, payload: RewardPayload) { enqueued.add(playerUuid) }
        }
        success = DonationSuccessService(
            rewardSink = sink,
            playerDao = PlayerDao(database),
            logger = logger,
            config = { cfg },
            broadcaster = {},
        )
        return ManualCreditService(
            database, card, bank, totals, PlayerDao(database),
            currency, success, TestSchedulers.direct(), logger, config = { cfg },
        )
    }

    @BeforeEach
    fun setUp() {
        database = Database(PluginConfig.DatabaseConfig(null), tempDir, logger).apply { connect(); migrate() }
        card = CardPaymentDao(database)
        bank = BankPaymentDao(database)
        totals = PlayerTotalsDao(database)
        service = buildService()
    }

    @AfterEach
    fun tearDown() = database.close()

    private fun totalAll(uuid: UUID, method: String): Long = database.withConnection { conn ->
        conn.prepareStatement(
            "SELECT amount_vnd FROM player_totals WHERE player_uuid = ? AND period = 'ALL' AND method = ?",
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, method)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    private fun row(table: String, uuid: UUID): Triple<String, String, Long> = database.withConnection { conn ->
        val column = if (table == "card_payments") "card_provider" else "provider"
        conn.prepareStatement("SELECT $column, status, point FROM $table WHERE player_uuid = ?").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs ->
                rs.next()
                Triple(rs.getString(1), rs.getString(2), rs.getLong("point"))
            }
        }
    }

    @Test
    fun `bank bucket credits flat-rate points, totals and reward, tagged manual`() {
        val uuid = UUID.randomUUID()
        service.give(ManualCreditService.Bucket.BANK, uuid, "Alice", 50_000, null, "AdminOp")
        assertEquals(50L, fakeCurrency.balance(uuid)) // 50_000/1000 * 1.0, no promo
        assertEquals(50_000L, totalAll(uuid, "bank"))
        assertEquals(1, enqueued.size)
        val (provider, status, point) = row("bank_payments", uuid)
        assertEquals("manual", provider)
        assertEquals("SUCCESS", status)
        assertEquals(50L, point)
    }

    @Test
    fun `point override replaces the flat-rate default`() {
        val uuid = UUID.randomUUID()
        service.give(ManualCreditService.Bucket.BANK, uuid, "Bob", 50_000, 999, "AdminOp")
        assertEquals(999L, fakeCurrency.balance(uuid))
        assertEquals(999L, row("bank_payments", uuid).third)
    }

    @Test
    fun `card bucket accrues to the card totals with a custom, non-denomination amount`() {
        val uuid = UUID.randomUUID()
        service.give(ManualCreditService.Bucket.CARD, uuid, "Carol", 137_000, null, "AdminOp")
        assertEquals(137L, fakeCurrency.balance(uuid)) // 137_000/1000 * 1.0
        assertEquals(137_000L, totalAll(uuid, "card"))
        assertEquals("manual", row("card_payments", uuid).first)
    }

    @Test
    fun `default points ignore any promo (flat rate only)`() {
        // ManualCreditService has no PromoService dependency: the default is purely bank point-rate.
        service = buildService(pointRate = 2.0)
        val uuid = UUID.randomUUID()
        service.give(ManualCreditService.Bucket.BANK, uuid, "Dave", 50_000, null, "AdminOp")
        assertEquals(100L, fakeCurrency.balance(uuid)) // 50 * 2.0, no bonus multiplier
    }

    @Test
    fun `passes the issuing actor and manual provider to the success hook for auditing`() {
        val captured = mutableListOf<Donation>()
        success.auditHook = { captured.add(it) }
        val uuid = UUID.randomUUID()
        service.give(ManualCreditService.Bucket.BANK, uuid, "Eve", 50_000, null, "AdminSteve")
        assertEquals(1, captured.size)
        assertEquals("AdminSteve", captured.single().actor)
        assertEquals("manual", captured.single().provider)
    }
}
