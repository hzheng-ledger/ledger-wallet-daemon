package co.ledger.wallet.daemon.database

import co.ledger.core.{Account, Wallet}
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext.Implicits.global
import co.ledger.wallet.daemon.database.core.WalletPoolDao
import co.ledger.wallet.daemon.models.Account._
import co.ledger.wallet.daemon.models.Currency._
import co.ledger.wallet.daemon.models.Wallet._
import co.ledger.wallet.daemon.models.coins.EthereumTransactionView
import co.ledger.wallet.daemon.models.{AccountExtendedDerivationView, ExtendedDerivationView, Pool, PoolInfo}
import co.ledger.wallet.daemon.services.Synced
import co.ledger.wallet.daemon.utils.NativeLibLoader
import com.twitter.inject.Logging
import com.twitter.util.{Await => TwitterAwait}
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

@Test
class WalletPoolDAOTest extends AssertionsForJUnit with Logging {
  NativeLibLoader.loadLibs()

  val xpubBtc1: String = "xpub6D4waFVPfPCpefXd5Rwb9TRuhoW7WZfYTS4cjv6Cw7ZBvFURHFFVYV2GF8hD36r31iDwBuP71TAEmy9596SnA7Hi6bgCLo6DYb7UUVqWWPA"
  val xpubEth1: String = "XPUBETH"
  val daemonCache: DaemonCache = new DefaultDaemonCache()
  val poolName = "walletpooldao"
  val pool: Pool = Await.result(daemonCache.createWalletPool(PoolInfo(poolName), ""), Duration.Inf) // Pool.newPoolInstance(PoolDto(poolName, "", Some(1))).get

  def createWallet(walletName: String): Wallet = {
    Await.result(pool.addWalletIfNotExist(walletName, walletName, isNativeSegwit = false), Duration.Inf)
  }

  def createAccountAndSync(wallet: Wallet, derivations: AccountExtendedDerivationView): Account = {
    val account = Await.result(wallet.addAccountIfNotExist(derivations), Duration.Inf)
    Await.result(account.sync(poolName, wallet.getName), Duration.Inf)
    account
  }

  @Test def testBitcoinWallet(): Unit = {
    val poolDao = new WalletPoolDao(poolName)
    val walletName = "bitcoin"

    val wallet = createWallet(walletName)
    val derivations = AccountExtendedDerivationView(0,
      Seq[ExtendedDerivationView](ExtendedDerivationView("44'/0'/0'", "main", Some(xpubBtc1))))

    val account = createAccountAndSync(wallet, derivations)

    val allOperations = TwitterAwait.result(poolDao.listAllOperations(account, wallet, 0, 1000))
    logger.info(s" All operations : ${allOperations.size}")

    val filteredOperations = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "20b028fbccb1329be368c680a1e884139b1b07f9501726b9b911f60b5c6c855f",
          "f802a15c2c5a654fa42ae65e46158f30feb8a07ecaf26ed2d64ed90da1fe7cf7",
          "3479299812467aa58192e0bc0a54c085ab11db2f5172bdbc4b6273ea791e82d4"
        ), 0, 100))
    assert(filteredOperations.size == 3)
    val filteredOperationsWithLimit = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "20b028fbccb1329be368c680a1e884139b1b07f9501726b9b911f60b5c6c855f",
          "f802a15c2c5a654fa42ae65e46158f30feb8a07ecaf26ed2d64ed90da1fe7cf7",
          "3479299812467aa58192e0bc0a54c085ab11db2f5172bdbc4b6273ea791e82d4"
        ), 0, 2))
    assert(filteredOperationsWithLimit.size == 2)
    val filteredOperationsWithOffset = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "20b028fbccb1329be368c680a1e884139b1b07f9501726b9b911f60b5c6c855f",
          "f802a15c2c5a654fa42ae65e46158f30feb8a07ecaf26ed2d64ed90da1fe7cf7",
          "3479299812467aa58192e0bc0a54c085ab11db2f5172bdbc4b6273ea791e82d4"
        ), 1, 10))
    assert(filteredOperationsWithOffset.size == 2)
    val opCounts = TwitterAwait.result(poolDao.countOperations(account, wallet))
    val countByType = allOperations.groupBy(_.opType).mapValues(_.size)
    assert(opCounts == countByType)
    assert(opCounts.values.sum == allOperations.size)

    logger.info("Account view : " +
      s"${Await.result(account.accountView(pool, wallet, wallet.getCurrency.currencyView, Synced(0L)), Duration.Inf)}")
  }

  @Test def testEthereumWallet(): Unit = {
    val poolDao = new WalletPoolDao(poolName)
    val walletName = "ethereum"

    val wallet = createWallet(walletName)
    val derivations = AccountExtendedDerivationView(0,
      Seq[ExtendedDerivationView](ExtendedDerivationView("44'/60'/0'", "main", Some(xpubEth1))))

    val account = createAccountAndSync(wallet, derivations)

    val allOperations = TwitterAwait.result(poolDao.listAllOperations(account, wallet, 0, 100))
    logger.info(s" All operations : ${allOperations.size}")

    val filteredOperations = TwitterAwait.result(
      poolDao.findOperationsByUids(account, wallet,
        Seq(
          "97da657e44222c6d64b6c69148a8218cda2fbc8bca793901277b400fc7b853d2",
          "05471c7362d95dc1c1af68cb40c1e56f580ca71759c7811165b580dbef52bac9",
          "6bb9334bd95c08b348d46f77ce7bbad271fa7a3490d47cb5e8259cf966b6dda7"
        ), 0, 100))
    assert(filteredOperations.size == 3)

    // Expect 2 ERC20 operations
    val erc20OperationViews = allOperations.filter(_.transaction.exists(_.asInstanceOf[EthereumTransactionView].erc20.isDefined))
    assert(erc20OperationViews.size == 2)

    val erc20OpUids = Seq("0cb747196f93c1f0f2503b0c2e5c484602d226b9b642b35c1f126e5aa9451c6d", "4b6a47c302787c5e514d56126572dc76c07d3385285295d3cda7348766067c77")
    val erc20ByUids = TwitterAwait.result(poolDao.findERC20OperationsByUids(account, wallet, erc20OpUids, 0, Int.MaxValue))

    assert(erc20ByUids.size == 2)
    assert(erc20ByUids.map(_.uid).toSet == erc20OperationViews.map(_.uid).toSet)
  }

}
