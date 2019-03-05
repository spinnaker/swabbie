/*
 *
 *  Copyright 2018 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.spinnaker.swabbie.aws.snapshots

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.*
import com.netflix.spinnaker.kork.core.RetrySupport
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.*
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.exclusions.AccountExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.AllowListExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.LiteralExclusionPolicy
import com.netflix.spinnaker.swabbie.exclusions.NaiveExclusionPolicy
import com.netflix.spinnaker.swabbie.model.*
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.repository.*
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

object AmazonSnapshotHandlerTest {

  private val log: Logger = LoggerFactory.getLogger(this.javaClass)
  val objectMapper = ObjectMapper().registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  @Test
  fun `data convert`() {
    val raw = "{\"volumeId\":\"vol-160d2afc\",\"state\":\"completed\",\"progress\":\"100%\",\"volumeSize\":10,\"startTime\":1438719239000,\"tags\":[{\"class\":\"com.amazonaws.services.ec2.model.Tag\",\"value\":\"http://cpe.builds.test.netflix.net/\",\"build_host\":\"http://cpe.builds.test.netflix.net/\",\"key\":\"build_host\"},{\"class\":\"com.amazonaws.services.ec2.model.Tag\",\"value\":\"builds\",\"creator\":\"builds\",\"key\":\"creator\"},{\"class\":\"com.amazonaws.services.ec2.model.Tag\",\"value\":\"l10nservicegeneral-1.0-h6.4a59761/DSC-LPE-l10nservicegeneral-test-build/6\",\"appversion\":\"l10nservicegeneral-1.0-h6.4a59761/DSC-LPE-l10nservicegeneral-test-build/6\",\"key\":\"appversion\"},{\"class\":\"com.amazonaws.services.ec2.model.Tag\",\"value\":\"nflx-base-1.491-h442.9192f05\",\"base_ami_version\":\"nflx-base-1.491-h442.9192f05\",\"key\":\"base_ami_version\"}],\"description\":\"name=l10nservicegeneral, arch=x86_64, ancestor_name=trustybase-x86_64-201507101816-ebs, ancestor_id=ami-5b31f930, ancestor_version=nflx-base-1.491-h442.9192f05\",\"dataEncryptionKeyId\":null,\"kmsKeyId\":null,\"snapshotId\":\"snap-721b6e38\",\"ownerId\":\"179727101194\",\"encrypted\":false,\"class\":\"com.amazonaws.services.ec2.model.Snapshot\",\"ownerAlias\":null,\"stateMessage\":null}"
    val raw2 = "{\"volumeId\":\"vol-ffffffff\",\"state\":\"completed\",\"progress\":\"100%\",\"volumeSize\":3,\"startTime\":1542397011400,\"tags\":[],\"description\":\"Created by AWS-VMImport service for import-snap-fhcp6i74\",\"dataEncryptionKeyId\":null,\"kmsKeyId\":null,\"snapshotId\":\"snap-0333155d642f6fccb\",\"ownerId\":\"085178109370\",\"encrypted\":false,\"class\":\"com.amazonaws.services.ec2.model.Snapshot\",\"ownerAlias\":null,\"stateMessage\":null}"
    val snapshot: AmazonSnapshot = objectMapper.readValue(raw)
    val snapshot2: AmazonSnapshot = objectMapper.readValue(raw2)

    assert(snapshot.snapshotId == "snap-721b6e38")
    assert(snapshot2.snapshotId == "snap-0333155d642f6fccb")
  }

  @Test
  fun `image con`() {
    val raw = "{\"description\":\"name=clouddriver, arch=x86_64, ancestor_name=xenialbase-x86_64-201901230035-ebs, ancestor_id=ami-0fec712ac298b612f, ancestor_version=nflx-base-5.328.0-h1108.7154fd2\",\"kernelId\":null,\"class\":\"com.amazonaws.services.ec2.model.Image\",\"name\":\"clouddriver-1.1623.0-h1739.a209fcb-x86_64-20190130210356-xenial-hvm-sriov-ebs\",\"stateReason\":null,\"virtualizationType\":\"hvm\",\"blockDeviceMappings\":[{\"virtualName\":null,\"ebs\":{\"snapshotId\":\"snap-0fbf910c6568eaa40\",\"encrypted\":false,\"volumeSize\":10,\"deleteOnTermination\":true,\"kmsKeyId\":null,\"class\":\"com.amazonaws.services.ec2.model.EbsBlockDevice\",\"iops\":null,\"volumeType\":\"standard\"},\"class\":\"com.amazonaws.services.ec2.model.BlockDeviceMapping\",\"noDevice\":null,\"deviceName\":\"/dev/sda1\"},{\"virtualName\":\"ephemeral0\",\"ebs\":null,\"class\":\"com.amazonaws.services.ec2.model.BlockDeviceMapping\",\"noDevice\":null,\"deviceName\":\"/dev/sdb\"},{\"virtualName\":\"ephemeral1\",\"ebs\":null,\"class\":\"com.amazonaws.services.ec2.model.BlockDeviceMapping\",\"noDevice\":null,\"deviceName\":\"/dev/sdc\"},{\"virtualName\":\"ephemeral2\",\"ebs\":null,\"class\":\"com.amazonaws.services.ec2.model.BlockDeviceMapping\",\"noDevice\":null,\"deviceName\":\"/dev/sdd\"},{\"virtualName\":\"ephemeral3\",\"ebs\":null,\"class\":\"com.amazonaws.services.ec2.model.BlockDeviceMapping\",\"noDevice\":null,\"deviceName\":\"/dev/sde\"}],\"imageLocation\":\"179727101194/clouddriver-1.1623.0-h1739.a209fcb-x86_64-20190130210356-xenial-hvm-sriov-ebs\",\"public\":false,\"rootDeviceName\":\"/dev/sda1\",\"hypervisor\":\"xen\",\"sriovNetSupport\":\"simple\",\"rootDeviceType\":\"ebs\",\"platform\":null,\"imageOwnerAlias\":null,\"ownerId\":\"179727101194\",\"state\":\"available\",\"imageId\":\"ami-08366161198075aff\",\"imageType\":\"machine\",\"ramdiskId\":null,\"enaSupport\":true,\"productCodes\":[],\"creationDate\":\"2019-01-30T21:08:46.000Z\",\"architecture\":\"x86_64\"}"
    val image: AmazonImage = objectMapper.readValue(raw)
    assert( image.imageId == "ami-08366161198075aff")
  }

  private val front50ApplicationCache = mock<InMemoryCache<Application>>()
  private val accountProvider = mock<AccountProvider>()
  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val usedResourceRepository = mock<UsedResourceRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonSnapshot>>()
  private val clock = Clock.fixed(Instant.parse("2018-05-24T12:34:56Z"), ZoneOffset.UTC)
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val lockingService = Optional.empty<LockingService>()
  private val orcaService = mock<OrcaService>()
  private val snapshotProvider = mock<ResourceProvider<AmazonSnapshot>>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val swabbieProperties = SwabbieProperties().apply {}
  private val applicationUtils = ApplicationUtils(emptyList())
  private val dynamicConfigService = mock<DynamicConfigService>()

  private val subject = AmazonSnapshotHandler(
    clock = clock,
    registry = NoopRegistry(),
    notifiers = listOf(mock()),
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    exclusionPolicies = listOf(
      LiteralExclusionPolicy(),
      AllowListExclusionPolicy(front50ApplicationCache, accountProvider),
      NaiveExclusionPolicy()
    ),
    resourceOwnerResolver = resourceOwnerResolver,
    applicationEventPublisher = applicationEventPublisher,
    lockingService = lockingService,
    retrySupport = RetrySupport(),
    dynamicConfigService = dynamicConfigService,
    rules = listOf(OrphanedSnapshotRule()),
    snapshotProvider = snapshotProvider,
    orcaService = orcaService,
    applicationUtils = applicationUtils,
    taskTrackingRepository = taskTrackingRepository,
    resourceUseTrackingRepository = resourceUseTrackingRepository,
    usedResourceRepository = usedResourceRepository,
    swabbieProperties = swabbieProperties
  )

  @Test
  fun `description matching works`() {
    val d = "Created by AWS-VMImport service for import-snap-fh3h9fjz"
    val d2 = "Copied for DestinationAmi ami-abc5d1bc from SourceAmi ami-51505e46 for SourceSnapshot snap-0ea59b6dfbba3cf48. Task created on 1,482,344,892,652."
    val d3 = "snapshot of image.vmdk"
    assert(!subject.descriptionIsFromAutoBake(d))
    assert(!subject.descriptionIsFromAutoBake(d2))
    assert(!subject.descriptionIsFromAutoBake(d3))

    val d4 = "name=orca, arch=x86_64, ancestor_name=xenialbase-x86_64-201902202219-ebs, ancestor_id=ami-0fea682be4d892b0a, ancestor_version=nflx-base-5.344.0-h1137.cc92ef3"
    assert(subject.descriptionIsFromAutoBake(d4))
  }

  @BeforeEach
  fun setup() {
    whenever(accountProvider.getAccounts()) doReturn
      setOf(
        SpinnakerAccount(
          name = "test",
          accountId = "1234",
          type = "aws",
          edda = "http://edda",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false,
          environment = "test"
        ),
        SpinnakerAccount(
          name = "prod",
          accountId = "4321",
          type = "aws",
          edda = "http://edda",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false,
          environment = "prod"
        )
      )

    val params = Parameters(account = "1234", region = "us-east-1", environment = "test")
    whenever(snapshotProvider.getAll(params)) doReturn listOf(
      AmazonSnapshot(
        volumeId = "vol-000",
        state = "completed",
        progress = "100%",
        startTime = 1519943308000,
        volumeSize = 10,
        description = "name=swabbie, arch=x86_64, ancestor_name=xb-ebs, ancestor_id=ami-0000, ancestor_version=nflx-base-5",
        snapshotId = "snap-000",
        ownerId = "1234",
        encrypted = false,
        ownerAlias = null,
        stateMessage = null
      ),
      AmazonSnapshot(
        volumeId = "vol-111",
        state = "completed",
        progress = "100%",
        startTime = 1519943307000,
        volumeSize = 10,
        description = "name=swabbie, arch=x86_64, ancestor_name=xb-ebs, ancestor_id=ami-0000, ancestor_version=nflx-base-4",
        snapshotId = "snap-1111",
        ownerId = "1234",
        encrypted = false,
        ownerAlias = null,
        stateMessage = null
      )
    )
  }

  @AfterEach
  fun cleanup() {
    Mockito.validateMockitoUsage()
    reset(
      resourceRepository,
      snapshotProvider,
      accountProvider,
      applicationEventPublisher,
      resourceOwnerResolver,
      taskTrackingRepository,
      usedResourceRepository
    )
  }

  @Test
  fun `should handle snapshots`() {
    Assertions.assertTrue(subject.handles(getWorkConfiguration()))
  }

  @Test
  fun `should find snapshot cleanup candidates`() {
    subject.getCandidates(getWorkConfiguration()).let { snapshots ->
      snapshots!!.size shouldMatch equalTo(2)
    }
  }

  @Test
  fun `should fail if exception is thrown`() {
    val configuration = getWorkConfiguration()
    whenever(snapshotProvider.getAll(any())) doThrow
      IllegalStateException("oh wow error")

    Assertions.assertThrows(IllegalStateException::class.java) {
      subject.preProcessCandidates(subject.getCandidates(getWorkConfiguration()).orEmpty(), configuration)
    }
  }

  @Test
  fun `should find cleanup candidates, apply exclusion policies on them and mark them`() {
    val workConfiguration = getWorkConfiguration(
      maxAgeDays = 1,
      exclusionList = mutableListOf(
        Exclusion()
          .withType(ExclusionType.Allowlist.toString())
          .withAttributes(
            listOf(
              Attribute()
                .withKey("snapshotId")
                .withValue(
                  listOf("snap-000") // will exclude anything else not matching this imageId
                )
            )
          )
      )
    )

    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle

    subject.mark(workConfiguration, postMark = { print("Done") })

    // snap-111 is excluded by exclusion policies, specifically because snap-111 is not allowlisted
    verify(applicationEventPublisher, times(1)).publishEvent(
      check<MarkResourceEvent> { event ->
        Assertions.assertTrue(event.markedResource.resourceId == "snap-000")
      }
    )

    verify(resourceRepository, times(1)).upsert(any(), any())
  }

  @Test
  fun `should not mark snapshots if they are not from a bake`() {
    val params = Parameters(account = "1234", region = "us-east-1", environment = "test")
    whenever(snapshotProvider.getAll(params)) doReturn listOf(
      AmazonSnapshot(
        volumeId = "vol-000",
        state = "completed",
        progress = "100%",
        startTime = 1519943308000,
        volumeSize = 10,
        description = "i am a snapshot would you look at that",
        snapshotId = "snap-000",
        ownerId = "1234",
        encrypted = false,
        ownerAlias = null,
        stateMessage = null
      )
    )

    val workConfiguration = getWorkConfiguration()
    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle

    // description isn't from a bake, so it won't be marked
    subject.mark(workConfiguration) { print { "postMark" } }
    verify(applicationEventPublisher, times(0)).publishEvent(any<MarkResourceEvent>())
    verify(resourceRepository, times(0)).upsert(any(), any())
  }

  @Test
  fun `should not mark snapshots if they're in use`() {
    val params = Parameters(account = "1234", region = "us-east-1", environment = "test")
    whenever(usedResourceRepository.isUsed(SNAPSHOT, "snap-000", "aws:${params.region}:${params.account}")) doReturn true
    whenever(usedResourceRepository.isUsed(SNAPSHOT, "snap-1111", "aws:${params.region}:${params.account}")) doReturn true

    val workConfiguration = getWorkConfiguration()
    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle

    // both snapshots are in use, so they won't be marked
    subject.mark(workConfiguration) { print { "postMark" } }
    verify(applicationEventPublisher, times(0)).publishEvent(any<MarkResourceEvent>())
    verify(resourceRepository, times(0)).upsert(any(), any())
  }

  private fun getWorkConfiguration(
    isEnabled: Boolean = true,
    dryRunMode: Boolean = false,
    accountIds: List<String> = listOf("test"),
    regions: List<String> = listOf("us-east-1"),
    exclusionList: MutableList<Exclusion> = mutableListOf(),
    maxAgeDays: Int = 1
  ): WorkConfiguration {
    val swabbieProperties = SwabbieProperties().apply {
      dryRun = dryRunMode
      providers = listOf(
        CloudProviderConfiguration().apply {
          name = "aws"
          exclusions = mutableListOf()
          accounts = accountIds
          locations = regions
          resourceTypes = listOf(
            ResourceTypeConfiguration().apply {
              name = "snapshot"
              enabled = isEnabled
              dryRun = dryRunMode
              exclusions = exclusionList
              retention = 2
              maxAge = maxAgeDays
            }
          )
        }
      )
    }

    return WorkConfigurator(
      swabbieProperties = swabbieProperties,
      accountProvider = accountProvider,
      exclusionPolicies = listOf(AccountExclusionPolicy()),
      exclusionsSuppliers = Optional.empty()
    ).generateWorkConfigurations()[0]
  }
}
