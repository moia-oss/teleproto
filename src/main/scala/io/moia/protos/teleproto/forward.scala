/*
 * Copyright 2019 MOIA GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.moia.protos.teleproto

/**
  * Signals a macro for Protocol Buffers readers to not raise warnings about a forward compatible writer.
  * The signature is used to validate whether the annotation was placed based on the same assumption
  * (ignored fields, used default values etc.).
  * If the model (or even the Protocol Buffers definition) somehow change an error will be raised so that the change in
  * behavior must be verified.
  */
@SuppressWarnings(Array("UnusedMethodParameter", "ClassNames"))
class forward(signature: String) extends scala.annotation.Annotation {}
