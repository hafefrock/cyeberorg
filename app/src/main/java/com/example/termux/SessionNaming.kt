package com.example.termux

/** Pure helper (unit tested): smallest positive number not already used by an open session. */
object SessionNaming {
    fun lowestFreeNumber(used: Collection<Int>): Int {
        var n = 1
        while (n in used) n++
        return n
    }
}
