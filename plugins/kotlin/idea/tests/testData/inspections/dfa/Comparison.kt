// WITH_STDLIB
fun testConstant() {
    if (<warning descr="Condition '2 + 2 > 5' is always false">2 + 2 > 5</warning>) {
    }
}
fun testSimple(x : Int) {
    if (x > 5) {
        if (<warning descr="Condition 'x < 0' is always false">x < 0</warning>) {
            
        }
    }
}
fun testParameter(x : Int) {
    if (x > 5) {
        if (<warning descr="Condition 'x < 3' is always false">x < 3</warning>) {
        }
        if (<warning descr="Condition 'x < 6' is always false">x < 6</warning>) {}
        if (x <= 6) {}
    }
    if (!(x > 5)) {
        if (<warning descr="Condition 'x == 6' is always false">x == 6</warning>) {}
    }
    if (<warning descr="Condition 'x > 5 && x < 3' is always false">x > 5 && <warning descr="Condition 'x < 3' is always false when reached">x < 3</warning></warning>) { }
    if (<warning descr="Condition 'x > 3 || x < 5' is always true">x > 3 || <warning descr="Condition 'x < 5' is always true when reached">x < 5</warning></warning>) { }
}
fun test(x : Int) {
    var y = x
    var b = false
    if (<warning descr="Condition 'y != x' is always false">y != x</warning>) {}
    println(<weak_warning descr="Value of 'b' is always false">b</weak_warning>)
}
