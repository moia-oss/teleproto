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
  * Signals a macro for Protocol Buffers readers/writers to trace the compiled code on info.
  * Intended to debug the mappings.
  */
@SuppressWarnings(Array("UnusedMethodParameter", "ClassNames"))
class trace extends scala.annotation.Annotation {}
