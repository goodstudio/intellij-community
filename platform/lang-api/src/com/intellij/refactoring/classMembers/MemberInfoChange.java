/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package com.intellij.refactoring.classMembers;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;

public class MemberInfoChange<T extends PsiElement, U extends MemberInfoBase<T>> {
  @Unmodifiable
  private final Collection<U> myChangedMembers;

  public MemberInfoChange(@Unmodifiable Collection<U> changedMembers) {
    myChangedMembers = changedMembers;
  }

  @Unmodifiable
  public Collection<U> getChangedMembers() {
    return myChangedMembers;
  }
}
