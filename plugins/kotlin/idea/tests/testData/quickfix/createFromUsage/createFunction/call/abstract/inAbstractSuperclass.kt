// "Create abstract function 'bar'" "true"
// ERROR: Class 'Foo' is not abstract and does not implement abstract base class member public abstract fun bar(): Unit defined in A

abstract class A

class Foo : A() {
    fun foo() {
        <caret>bar()
    }
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.createFromUsage.createCallable.CreateCallableFromUsageFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.quickFixes.createFromUsage.CreateKotlinCallableAction