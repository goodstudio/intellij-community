// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// ATTACH_LIBRARY: utils
package stepIntoLambdaWithParameterDestructuring

import destructurableClasses.A
import destructurableClasses.B

fun foo(f: (A, Int, Int, B, Int, A, B) -> Unit) {
    val a = A(1, 1)
    val b = B()
    // STEP_INTO: 1
    //Breakpoint!
    f(a, 1, 1, b, 1, a, b)
}

fun main() {
    foo { (a, b),
          c,
          d, (e,
              f, g,
              h),
          i,
          (j, k),
          (l,
              m,
              n, o) ->
        println()
    }
}

// PRINT_FRAME
// SHOW_KOTLIN_VARIABLES

// EXPRESSION: a + b + c + d + e + f + g + h + i + j + k + l + m + n + o
// RESULT: Unresolved reference: a
// RESULT_K2_COMPILER: 15: I
