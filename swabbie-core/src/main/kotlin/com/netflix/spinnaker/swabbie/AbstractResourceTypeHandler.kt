/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.swabbie

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.kork.lock.LockManager
import com.netflix.spinnaker.kork.lock.LockManager.LockOptions
import com.netflix.spinnaker.swabbie.echo.EchoService
import com.netflix.spinnaker.swabbie.echo.Notifier
import com.netflix.spinnaker.swabbie.events.*
import com.netflix.spinnaker.swabbie.exclusions.ResourceExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import org.springframework.context.ApplicationEventPublisher
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractResourceTypeHandler<out T : Resource>(
  registry: Registry,
  private val clock: Clock,
  private val rules: List<Rule<T>>,
  private val resourceTrackingRepository: ResourceTrackingRepository,
  private val exclusionPolicies: List<ResourceExclusionPolicy>,
  private val ownerResolver: OwnerResolver<T>,
  private val notifier: Notifier,
  private val applicationEventPublisher: ApplicationEventPublisher,
  private val lockManager: Optional<LockManager>
) : ResourceTypeHandler<T>, AgentMetricsSupport(registry) {

  /**
   * finds & tracks cleanup candidates
   */
  override fun mark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    execute(workConfiguration, postMark, Action.MARK)
  }

  /**
   * deletes violating resources
   */
  override fun clean(workConfiguration: WorkConfiguration, postClean: () -> Unit) {
    execute(workConfiguration, postClean, Action.DELETE)
  }

  /**
   * Notifies resource owners
   */
  override fun notify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    execute(workConfiguration, postNotify, Action.NOTIFY)
  }

  private fun execute(workConfiguration: WorkConfiguration, callback: () -> Unit, action: Action) {
    val normalizedName = workConfiguration.namespace
      .replace(":", ".")
      .toLowerCase()

    val useLocks = lockManager.isPresent
    val lockOptions = LockOptions()
      .withLockName("${action.name}.$normalizedName")
      .withMaximumLockDuration(Duration.ofSeconds(3600))

    when (action) {
      Action.MARK -> withLocking(useLocks, lockOptions, {
        doMark(workConfiguration, callback)
      })
      Action.DELETE -> withLocking(useLocks, lockOptions, {
        doClean(workConfiguration, callback)
      })
      Action.NOTIFY -> withLocking(useLocks, lockOptions, {
        doNotify(workConfiguration, callback)
      })
      else -> log.warn("Invalid action {}", action.name)
    }
  }

  private fun withLocking(useLocks: Boolean, lockOptions: LockOptions, callback: () -> Unit) {
    if (useLocks) {
      lockManager.get().acquireLock(lockOptions, {
        callback.invoke()
      })
    } else {
      log.warn("***Locking not ENABLED")
      callback.invoke()
    }
  }

  private fun doMark(workConfiguration: WorkConfiguration, postMark: () -> Unit) {
    // initialize counters
    resourcesExcludedCounter.set(0)
    val timerId = markDurationTimer.start()
    val candidateCounter = AtomicInteger(0)
    val violationCounter = AtomicInteger(0)
    val totalResourcesVisitedCounter = AtomicInteger(0)
    try {
      log.info("${javaClass.name}: getting resources with namespace {}, dryRun {}",
        workConfiguration.namespace, workConfiguration.dryRun)

      getUpstreamResources(workConfiguration)?.let { upstreamResources ->
        totalResourcesVisitedCounter.set(upstreamResources.size)
        log.info("fetched {} resources with namespace {}, dryRun {}",
          upstreamResources.size, workConfiguration.namespace, workConfiguration.dryRun)

        upstreamResources.filter {
          !shouldExclude(it, workConfiguration)
        }.forEach { upstreamResource ->
          rules.mapNotNull {
            it.apply(upstreamResource).summary
          }.let { violationSummaries ->
            resourceTrackingRepository.find(
              resourceId = upstreamResource.resourceId,
              namespace = workConfiguration.namespace
            ).let { trackedMarkedResource ->
              if (trackedMarkedResource != null && violationSummaries.isEmpty() && !workConfiguration.dryRun) {
                log.info("Forgetting now valid resource {}", upstreamResource)

                resourceTrackingRepository.remove(trackedMarkedResource)
                applicationEventPublisher.publishEvent(UnMarkResourceEvent(trackedMarkedResource, workConfiguration))
              } else if (!violationSummaries.isEmpty() && trackedMarkedResource == null) {
                violationCounter.addAndGet(violationSummaries.size)
                MarkedResource(
                  resource = upstreamResource,
                  summaries = violationSummaries,
                  namespace = workConfiguration.namespace,
                  resourceOwner = ownerResolver.resolve(upstreamResource),
                  projectedDeletionStamp = workConfiguration.retentionDays.days.
                    fromNow.atStartOfDay(clock.zone).toInstant().toEpochMilli()
                ).let {
                  candidateCounter.incrementAndGet()
                  if (!workConfiguration.dryRun) {
                    resourceTrackingRepository.upsert(it)
                    log.info("Marking resource {} for deletion", it)
                    applicationEventPublisher.publishEvent(MarkResourceEvent(it, workConfiguration))
                  }
                }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      recordFailureForAction(Action.MARK, workConfiguration, e)
    } finally {
      recordMarkMetrics(timerId, workConfiguration, violationCounter, candidateCounter)
      postMark.invoke()
    }
  }

  private fun shouldExclude(resource: T, workConfiguration: WorkConfiguration): Boolean =
    resource.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions).also {
      if (it) {
        log.info("Excluding resource {} out of {}", resource, resourcesExcludedCounter.incrementAndGet())
      }
    }

  private fun doClean(workConfiguration: WorkConfiguration, postClean: () -> Unit) {
    try {
      resourceTrackingRepository.getMarkedResourcesToDelete().let { markedResources ->
        if (markedResources == null || markedResources.isEmpty()) {
          log.info("No resources to delete for {}", workConfiguration)
        } else {
          markedResources.forEach { markedResource ->
            getUpstreamResource(markedResource, workConfiguration)
              .let { resource ->
                if (resource == null) {
                  log.info("Resource {} no longer exists", markedResource)
                  resourceTrackingRepository.remove(markedResource)
                } else {
                  resource
                    .takeIf { !it.shouldBeExcluded(exclusionPolicies, workConfiguration.exclusions) }
                    ?.let { upstreamResource ->
                      rules.mapNotNull {
                        it.apply(upstreamResource).summary
                      }.let { violationSummaries ->
                        if (violationSummaries.isEmpty() && !workConfiguration.dryRun) {
                          applicationEventPublisher.publishEvent(UnMarkResourceEvent(markedResource, workConfiguration))
                          resourceTrackingRepository.remove(markedResource)
                        } else {
                          log.info("Preparing deletion of {}. dryRun {}", markedResource, workConfiguration.dryRun)
                          if (markedResource.notificationInfo != null && !workConfiguration.dryRun) {
                            remove(markedResource, workConfiguration)
                            resourceTrackingRepository.remove(markedResource)
                            applicationEventPublisher.publishEvent(DeleteResourceEvent(markedResource, workConfiguration))
                            log.info("Deleted {}", resource)
                          }
                        }
                      }
                    }
                }
              }
          }
        }
      }
    } catch (e: Exception) {
      recordFailureForAction(Action.DELETE, workConfiguration, e)
    } finally {
      postClean.invoke()
    }
  }

  private fun doNotify(workConfiguration: WorkConfiguration, postNotify: () -> Unit) {
    try {
      log.info("Notification Agent Started with configuration {}", workConfiguration)
      val owners = mutableMapOf<String, MutableList<MarkedResource>>()
      resourceTrackingRepository.getMarkedResources()
        ?.filter {
          workConfiguration.namespace.equals(it.namespace, ignoreCase = true) && it.notificationInfo == null
        }?.forEach { markedResource ->
          markedResource.resourceOwner?.let { owner ->
            if (owners[owner] == null) {
              owners[owner] = mutableListOf(markedResource)
            } else {
              owners[owner]!!.add(markedResource)
            }
          }
        }

      owners.let { ownersToResources ->
        ownersToResources.forEach { ownerToResources ->
          log.info("DryRun={}, shouldNotify={}, user={}, {} cleanup candidate(s)",
            workConfiguration.dryRun,
            workConfiguration.notificationConfiguration!!.notifyOwner,
            ownerToResources.key,
            ownerToResources.value.size
          )

          if (workConfiguration.dryRun || !workConfiguration.notificationConfiguration.notifyOwner) {
            log.info("Skipping notifications for {}", workConfiguration)
          } else {
            ownerToResources.value.let { resources ->
              notifier.notify(
                recipient = ownerToResources.key,
                messageType = EchoService.Notification.Type.EMAIL.name,
                additionalContext = mapOf(
                  "resourceOwner" to ownerToResources.key,
                  "resources" to resources,
                  "configuration" to workConfiguration,
                  "resourceType" to workConfiguration.resourceType.formatted(),
                  "spinnakerLink" to workConfiguration.notificationConfiguration.spinnakerResourceUrl,
                  "optOutLink" to workConfiguration.notificationConfiguration.optOutUrl
                )
              ).let {
                log.info("Notification sent to {} for {}", ownerToResources.key, resources)
                resources.forEach { resource ->
                  val offsetStampSinceMarked: Long = ChronoUnit.MILLIS.between(
                    Instant.ofEpochMilli(resource.createdTs!!),
                    Instant.now(clock)
                  )

                  resource.apply {
                    projectedDeletionStamp += offsetStampSinceMarked
                    notificationInfo = NotificationInfo(
                      recipient = ownerToResources.key,
                      notificationStamp = Instant.now(clock).toEpochMilli(),
                      notificationType = EchoService.Notification.Type.EMAIL.name
                    )
                  }.also {
                    resourceTrackingRepository.upsert(it)
                    applicationEventPublisher.publishEvent(OwnerNotifiedEvent(resource, workConfiguration))
                  }
                }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      recordFailureForAction(Action.NOTIFY, workConfiguration, e)
    } finally {
      postNotify.invoke()
    }
  }

  private val Int.days: Period
    get() = Period.ofDays(this)

  private val Period.fromNow: LocalDate
    get() = LocalDate.now(clock) + this

  abstract fun remove(markedResource: MarkedResource, workConfiguration: WorkConfiguration)
}
