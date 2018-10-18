package com.netflix.spinnaker.swabbie.edda.providers

import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.InMemoryCache
import com.netflix.spinnaker.swabbie.edda.EddaEndpointsService
import com.netflix.spinnaker.swabbie.model.Account
import com.netflix.spinnaker.swabbie.model.Region
import com.netflix.spinnaker.swabbie.model.SpinnakerAccount
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

@ConditionalOnExpression("\${eddaEndpoints.enabled:false}")
@Component
class EddaAccountProvider(
  private val eddaEndpointsService: EddaEndpointsService,
  @Lazy private val accountCache: InMemoryCache<SpinnakerAccount>
) : AccountProvider {
  override fun getAccounts(): Set<Account> = accountCache.get()

  fun load(): Set<SpinnakerAccount> {
    return eddaEndpointsService.getEddaEndpoints().get()
      .asSequence()
      .mapNotNull { endpointToAccount(it) }
      .toSet()
  }

  // i.e. "http://edda-account.region.foo.bar.com",
  private fun endpointToAccount(endpoint: String): SpinnakerAccount? {
    val regex = """^https?://edda-([\w\-]+)\.([\w\-]+)\..*$""".toRegex()
    val match = regex.matchEntire(endpoint) ?: return null
    val (account, region) = match.destructured

    return SpinnakerAccount(true, null, "aws", account, endpoint, listOf(Region(false, region)))
  }
}

@ConditionalOnExpression("\${eddaEndpoints.enabled:false}")
@Component
class EddaAccountCache(
  eddaAccountProvider: EddaAccountProvider
) : InMemoryCache<SpinnakerAccount>(eddaAccountProvider.load())
