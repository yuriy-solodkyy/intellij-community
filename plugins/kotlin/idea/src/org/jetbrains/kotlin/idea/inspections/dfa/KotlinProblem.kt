// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.inspections.dfa

import com.intellij.codeInspection.dataFlow.jvm.problems.IndexOutOfBoundsProblem
import com.intellij.codeInspection.dataFlow.lang.UnsatisfiedConditionProblem
import com.intellij.codeInspection.dataFlow.value.DerivedVariableDescriptor
import org.jetbrains.kotlin.psi.KtExpression

sealed class KotlinProblem: UnsatisfiedConditionProblem {
    data class KotlinCastProblem(val operand: KtExpression, val cast: KtExpression): KotlinProblem()
    data class KotlinArrayIndexProblem(private val lengthDescriptor: DerivedVariableDescriptor, val index: KtExpression):
        KotlinProblem(), IndexOutOfBoundsProblem {
        override fun getLengthDescriptor(): DerivedVariableDescriptor = lengthDescriptor
    }
}