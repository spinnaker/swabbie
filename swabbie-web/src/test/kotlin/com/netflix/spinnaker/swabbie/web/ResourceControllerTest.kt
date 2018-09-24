package com.netflix.spinnaker.swabbie.web

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.config.ExclusionType
import com.netflix.spinnaker.swabbie.AccountProvider
import com.netflix.spinnaker.swabbie.ResourceStateRepository
import com.netflix.spinnaker.swabbie.ResourceTrackingRepository
import com.netflix.spinnaker.swabbie.ResourceTypeHandler
import com.netflix.spinnaker.swabbie.aws.images.AmazonImage
import com.netflix.spinnaker.swabbie.aws.images.AmazonImageHandler
import com.netflix.spinnaker.swabbie.controllers.Namespace
import com.netflix.spinnaker.swabbie.controllers.ResourceController
import com.netflix.spinnaker.swabbie.model.*
import com.nhaarman.mockito_kotlin.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime

object ResourceControllerTest {
  private val resourceStateRepository = mock<ResourceStateRepository>()
  private val resourceTrackingRepository = mock<ResourceTrackingRepository>()
  private val applicationEventPublisher = mock<ApplicationEventPublisher>()
  private val workConfigurations = mock<List<WorkConfiguration>>()
  private val accountProvider = mock<AccountProvider>()
  private val resourceTypeHandlers = mock<List<ResourceTypeHandler<*>>>()

  private val subject = ResourceController(
    resourceStateRepository,
    resourceTrackingRepository,
    applicationEventPublisher,
    workConfigurations,
    accountProvider,
    resourceTypeHandlers
  )

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
          eddaEnabled = false
        ),
        SpinnakerAccount(
          name = "prod",
          accountId = "4321",
          type = "aws",
          edda = "http://edda",
          regions = listOf(Region(name = "us-east-1")),
          eddaEnabled = false
        )
      )

    val image = AmazonImage(
      imageId = "ami-123",
      resourceId = "ami-123",
      description = "ancestor_id=ami-122",
      ownerId = null,
      state = "available",
      resourceType = IMAGE,
      cloudProvider = AWS,
      name = "123-xenial-hvm-sriov-ebs",
      creationDate = LocalDateTime.now().minusDays(3).toString()
    )

    val oneDayAgo = System.currentTimeMillis() - 1 * 24 * 60 * 60 * 1000L
    val tenDaysFromNow = System.currentTimeMillis() + 10 * 24 * 60 * 60 * 1000L

    whenever(resourceTrackingRepository.getMarkedResources()) doReturn
      listOf(
        MarkedResource(
          resource = image,
          summaries = listOf(Summary("Image is unused", "testRule 1")),
          namespace = "aws:test:us-east-1:image",
          resourceOwner = "test@netflix.com",
          projectedDeletionStamp = tenDaysFromNow,
          notificationInfo = NotificationInfo(
            recipient = "test@netflix.com",
            notificationType = "Email",
            notificationStamp = oneDayAgo
          )
        )
      )
  }

  @AfterEach
  fun cleanup() {
    validateMockitoUsage()
    reset(accountProvider, applicationEventPublisher)
  }

  @Test
  fun `returns marked resources`() {
    subject.markedResources(false).size shouldMatch equalTo(1)
  }

  @Test
  fun `generates correct work configuration`() {
    val namespace = Namespace("aws", "test", "us-east-1", "image")

    val workConfiguration = subject.getWorkConfiguration(namespace)

    workConfiguration.account.accountId shouldMatch equalTo("1234")
    workConfiguration.namespace shouldMatch equalTo(namespace.toString())
    workConfiguration.exclusions shouldMatch equalTo(listOf())
  }
}
