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
import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.config.SwabbieProperties
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.swabbie.aws.Parameters
import com.netflix.spinnaker.swabbie.ResourceOwnerResolver
import com.netflix.spinnaker.swabbie.aws.AWS
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.events.MarkResourceEvent
import com.netflix.spinnaker.swabbie.model.SNAPSHOT
import com.netflix.spinnaker.swabbie.model.AWS
import com.netflix.spinnaker.swabbie.model.Rule
import com.netflix.spinnaker.swabbie.model.Summary
import com.netflix.spinnaker.swabbie.notifications.NotificationQueue
import com.netflix.spinnaker.swabbie.orca.OrcaService
import com.netflix.spinnaker.swabbie.repository.ResourceStateRepository
import com.netflix.spinnaker.swabbie.repository.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.repository.ResourceUseTrackingRepository
import com.netflix.spinnaker.swabbie.repository.TaskTrackingRepository
import com.netflix.spinnaker.swabbie.repository.UsedResourceRepository
import com.netflix.spinnaker.swabbie.rules.ResourceRulesEngine
import com.netflix.spinnaker.swabbie.test.WorkConfigurationTestHelper
import com.netflix.spinnaker.swabbie.utils.ApplicationUtils
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.validateMockitoUsage
import com.nhaarman.mockito_kotlin.verifyNoMoreInteractions
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.doThrow
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNull
import strikt.assertions.isTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

object AmazonSnapshotHandlerTest {
  private val objectMapper = ObjectMapper().registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

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
    assert(image.imageId == "ami-08366161198075aff")
  }

  private val resourceRepository = mock<ResourceTrackingRepository>()
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val usedResourceRepository = mock<UsedResourceRepository>()
  private val resourceOwnerResolver = mock<ResourceOwnerResolver<AmazonSnapshot>>()
  private val clock = Clock.fixed(Instant.parse("2018-05-24T12:34:56Z"), ZoneOffset.UTC)
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val orcaService = mock<OrcaService>()
  private val aws = mock<AWS>()
  private val taskTrackingRepository = mock<TaskTrackingRepository>()
  private val resourceUseTrackingRepository = mock<ResourceUseTrackingRepository>()
  private val swabbieProperties = SwabbieProperties().apply {}
  private val applicationUtils = ApplicationUtils(emptyList())
  private val dynamicConfigService = mock<DynamicConfigService>()
  private val notificationQueue = mock<NotificationQueue>()
  private val rulesEngine = mock<ResourceRulesEngine>()
  private val ruleAndViolationPair = Pair<Rule, List<Summary>>(mock(), listOf(Summary("violate rule", ruleName = "rule")))
  private val workConfiguration = WorkConfigurationTestHelper
    .generateWorkConfiguration(resourceType = SNAPSHOT, cloudProvider = AWS)

  private val params = Parameters(
    account = workConfiguration.account.accountId!!,
    region = workConfiguration.location,
    environment = workConfiguration.account.environment
  )

  private val subject = AmazonSnapshotHandler(
    clock = clock,
    registry = NoopRegistry(),
    notifier = mock(),
    resourceTrackingRepository = resourceRepository,
    resourceStateRepository = resourceStateRepository,
    exclusionPolicies = listOf(),
    resourceOwnerResolver = resourceOwnerResolver,
    applicationEventPublisher = applicationEventPublisher,
    dynamicConfigService = dynamicConfigService,
    rulesEngine = rulesEngine,
    aws = aws,
    orcaService = orcaService,
    applicationUtils = applicationUtils,
    taskTrackingRepository = taskTrackingRepository,
    resourceUseTrackingRepository = resourceUseTrackingRepository,
    usedResourceRepository = usedResourceRepository,
    swabbieProperties = swabbieProperties,
    notificationQueue = notificationQueue
  )

  private val snap000 = AmazonSnapshot(
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
  )

  private val snap111 = AmazonSnapshot(
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

  @BeforeEach
  fun setup() {
    snap000.details.clear()
    snap111.details.clear()
    whenever(dynamicConfigService.getConfig(any(), any(), eq(workConfiguration.maxItemsProcessedPerCycle))) doReturn
      workConfiguration.maxItemsProcessedPerCycle

    whenever(aws.getSnapshots(params)) doReturn listOf(snap000, snap111)
  }

  @AfterEach
  fun cleanup() {
    validateMockitoUsage()
    reset(
      resourceRepository,
      aws,
      applicationEventPublisher,
      resourceOwnerResolver,
      taskTrackingRepository,
      usedResourceRepository
    )
  }

  @Test
  fun `should handle snapshots`() {
    whenever(rulesEngine.getRules(workConfiguration)) doReturn listOf(ruleAndViolationPair.first)
    expectThat(subject.handles(workConfiguration)).isTrue()

    whenever(rulesEngine.getRules(workConfiguration)) doReturn emptyList<Rule>()
    expectThat(subject.handles(workConfiguration)).isFalse()
  }

  @Test
  fun `should get snapshots`() {
    expectThat(subject.getCandidates(workConfiguration)!!.count()).isEqualTo(2)
  }

  @Test
  fun `should fail if exception is thrown`() {
    whenever(aws.getSnapshots(any())) doThrow
      IllegalStateException("oh wow error")

    Assertions.assertThrows(IllegalStateException::class.java) {
      subject.preProcessCandidates(subject.getCandidates(workConfiguration).orEmpty(), workConfiguration)
    }
  }

  @Test
  fun `should mark snapshots`() {
    whenever(rulesEngine.evaluate(any<AmazonSnapshot>(), any())) doReturn ruleAndViolationPair.second
    subject.mark(workConfiguration)

    verify(resourceRepository, times(2)).upsert(any(), any())
    verify(applicationEventPublisher, times(2)).publishEvent(any<MarkResourceEvent>())
    verifyNoMoreInteractions(applicationEventPublisher)
  }

  @Test
  fun `should set image exists`() {
    whenever(
      usedResourceRepository.isUsed(SNAPSHOT, snap000.snapshotId, "aws:${params.region}:${params.account}")
    ) doReturn true

    whenever(
      usedResourceRepository.isUsed(SNAPSHOT, snap111.snapshotId, "aws:${params.region}:${params.account}")
    ) doReturn false

    expectThat(snap000.details[IMAGE_EXISTS]).isNull()

    subject.preProcessCandidates(listOf(snap000, snap111), workConfiguration)

    expectThat(snap000.details[IMAGE_EXISTS]).isEqualTo(true)
    expectThat(snap111.details[IMAGE_EXISTS]).isNull()
  }
}
