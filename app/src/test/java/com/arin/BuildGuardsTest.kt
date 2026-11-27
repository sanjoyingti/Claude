package com.arin

import com.arin.builder.KotlinImports
import com.arin.builder.KotlinSyntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The no-AI build repairs from 1.15.1–1.15.4. */
class BuildGuardsTest {
    private val good = "package a\n\nclass A {\n    fun f() {\n        val s = \"x { \${1 + 2} }\"\n        val c = '}'\n    }\n}\n"

    @Test fun balancedFileIsOk() = assertTrue(KotlinSyntax.scan(good).ok)
    @Test fun strayBraceIsRepaired() = assertEquals(good.trim(), KotlinSyntax.repair("A.kt", good + "}\n")!!.trim())
    @Test fun missingBraceIsRepaired() = assertEquals(good.trim(), KotlinSyntax.repair("A.kt", good.removeSuffix("}\n"))!!.trim())
    @Test fun fencesAndProseAreRemoved() =
        assertEquals(good.trim(), KotlinSyntax.repair("A.kt", "Here is the fix:\n```kotlin\n$good```\nThat should work.")!!.trim())

    @Test fun importsInTheMiddleMoveToTheTop() {
        val broken = "package a\n\nimport x.Y\n\nclass A {\n    fun f() {}\nimport androidx.compose.material.icons.filled.Star\n}\n"
        assertTrue(KotlinSyntax.misplacedImports(broken))
        val fixed = KotlinSyntax.repair("A.kt", broken)!!
        assertFalse(KotlinSyntax.misplacedImports(fixed))
        assertTrue(fixed.indexOf("import androidx.compose.material.icons.filled.Star") < fixed.indexOf("class A"))
    }

    @Test fun missingComposeImportsAreAdded() {
        val src = "package a\n\n@Composable\nfun Home() {\n    var n by remember { mutableStateOf(0) }\n    Text(\"\$n\", Modifier.padding(8.dp))\n}\n"
        val (out, _) = KotlinImports.fix("Home.kt", src, emptySet())
        listOf("androidx.compose.runtime.Composable", "androidx.compose.runtime.remember", "androidx.compose.runtime.mutableStateOf",
            "androidx.compose.runtime.getValue", "androidx.compose.runtime.setValue", "androidx.compose.material3.Text",
            "androidx.compose.ui.Modifier", "androidx.compose.foundation.layout.padding", "androidx.compose.ui.unit.dp")
            .forEach { assertTrue("missing $it", out.contains("import $it")) }
    }

    @Test fun projectOwnNamesAreNotImported() {
        val src = "package a\n\n@Composable\nfun Home() { Text(\"x\") }\n"
        val (out, _) = KotlinImports.fix("Home.kt", src, setOf("Text"))
        assertFalse(out.contains("import androidx.compose.material3.Text"))
    }
}
