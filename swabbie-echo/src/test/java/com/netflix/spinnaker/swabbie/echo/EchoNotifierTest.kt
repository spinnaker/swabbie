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

package com.netflix.spinnaker.swabbie.echo

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import org.junit.jupiter.api.Test

object EchoNotifierTest {
  private val echoService = mock<EchoService>()
  private val subject = EchoNotifier(echoService)

  @Test
  fun `should notify`() {
    subject.notify(
      recipient = "yolo@netflix.com",
      subject = "subject",
      body = "body",
      messageType = EchoService.Notification.Type.EMAIL.name
    )

    verify(echoService).create(any())
  }
}
