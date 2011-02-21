/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Represents a resource list of try-with-resources statement (automatic resource management) introduced in JDK 7.
 *
 * @see PsiTryStatement#getResourceList()
 * @since 10.5.
 */
public interface PsiResourceList extends PsiElement {
  /**
   * Returns list of resource elements. Each element is either {@link PsiLocalVariable} or {@link PsiExpression}.
   *
   * @return list of resource elements.
   */
  @NotNull
  List<? extends PsiElement> getResources();

  /**
   * Returns list of named resource elements only.
   *
   * @return list of named resource elements.
   */
  @NotNull
  List<PsiLocalVariable> getNamedResources();
}
