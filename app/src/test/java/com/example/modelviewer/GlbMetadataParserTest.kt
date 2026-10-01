package com.example.modelviewer

import com.example.modelviewer.gltf.GlbFormatException
import com.example.modelviewer.gltf.GlbMetadataCache
import com.example.modelviewer.gltf.GlbMetadataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GlbMetadataParserTest {

    @Before fun clearCache() = GlbMetadataCache.clear()

    @Test fun extractsExtrasPropExactly() {
        val json = """{"nodes":[{"name":"Piston","translation":[0.2,1.4,0.0],"extras":{"prop":"Piston head"}}]}"""
        val meta = GlbMetadataParser.parseJson(json)
        assertEquals(1, meta.labels.size)
        val l = meta.labels[0]
        assertEquals("Piston head", l.text) // NOT the node name
        assertEquals("Piston", l.nodeName)
        assertEquals(0, l.nodeIndex)
        assertEquals(0.2f, l.anchorX, 1e-5f)
        assertEquals(1.4f, l.anchorY, 1e-5f)
        assertEquals(0f, l.anchorZ, 1e-5f)
    }

    @Test fun preservesTextWithoutTrimming() {
        val json = """{"nodes":[{"extras":{"prop":"  Crank  shaft "}}]}"""
        assertEquals("  Crank  shaft ", GlbMetadataParser.parseJson(json).labels.single().text)
    }

    @Test fun ignoresNodesWithoutUsableProp() {
        val json = """{"nodes":[
            {"name":"NoExtras"},
            {"name":"EmptyExtras","extras":{}},
            {"name":"OtherKey","extras":{"foo":"bar"}},
            {"name":"NullProp","extras":{"prop":null}},
            {"name":"BlankProp","extras":{"prop":"   "}},
            {"name":"EmptyProp","extras":{"prop":""}},
            {"name":"ExtrasNotObject","extras":"prop"},
            {"name":"Good","extras":{"prop":"Valve"}}
        ]}"""
        val labels = GlbMetadataParser.parseJson(json).labels
        assertEquals(listOf("Valve"), labels.map { it.text })
        assertEquals(7, labels.single().nodeIndex)
    }

    @Test fun anchorIsWorldSpaceThroughHierarchy() {
        val json = """{"scene":0,"scenes":[{"nodes":[0]}],"nodes":[
            {"name":"Parent","translation":[1,0,0],"scale":[2,2,2],"children":[1]},
            {"name":"Child","translation":[0,1,0],"extras":{"prop":"Child part"}}
        ]}"""
        val l = GlbMetadataParser.parseJson(json).labels.single()
        // world = T(1,0,0) * S(2) * (0,1,0) = (1, 2, 0)
        assertEquals(1f, l.anchorX, 1e-5f)
        assertEquals(2f, l.anchorY, 1e-5f)
        assertEquals(0f, l.anchorZ, 1e-5f)
    }

    @Test fun anchorHonoursRotationAndMatrix() {
        // 90 degrees about +Z: (0,1,0) child offset -> (-1,0,0)
        val s = Math.sqrt(0.5).toFloat()
        val rotated = """{"nodes":[
            {"rotation":[0,0,$s,$s],"children":[1]},
            {"translation":[0,1,0],"extras":{"prop":"A"}}
        ]}"""
        val a = GlbMetadataParser.parseJson(rotated).labels.single()
        assertEquals(-1f, a.anchorX, 1e-4f)
        assertEquals(0f, a.anchorY, 1e-4f)

        val matrix = """{"nodes":[{"matrix":[1,0,0,0, 0,1,0,0, 0,0,1,0, 5,6,7,1],"extras":{"prop":"M"}}]}"""
        val m = GlbMetadataParser.parseJson(matrix).labels.single()
        assertEquals(5f, m.anchorX, 1e-5f); assertEquals(6f, m.anchorY, 1e-5f); assertEquals(7f, m.anchorZ, 1e-5f)
    }

    @Test fun survivesCyclesAndBadIndices() {
        val json = """{"nodes":[
            {"children":[1,99,-1],"extras":{"prop":"A"}},
            {"children":[0],"extras":{"prop":"B"}}
        ]}"""
        assertEquals(2, GlbMetadataParser.parseJson(json).labels.size)
    }

    @Test fun missingOrEmptyNodesGivesNoLabels() {
        assertTrue(GlbMetadataParser.parseJson("""{"asset":{"version":"2.0"}}""").labels.isEmpty())
        assertTrue(GlbMetadataParser.parseJson("""{"nodes":[]}""").labels.isEmpty())
    }

    @Test fun nodeWithoutNameStillLabelled() {
        val l = GlbMetadataParser.parseJson("""{"nodes":[{"extras":{"prop":"Anon"}}]}""").labels.single()
        assertNull(l.nodeName)
        assertEquals("Anon", l.text)
    }

    @Test fun parsesFromGlbBytes() {
        val glb = buildGlb("""{"nodes":[{"name":"N","extras":{"prop":"From GLB"}}]}""")
        assertEquals("From GLB", GlbMetadataParser.parse(glb).labels.single().text)
    }

    @Test(expected = GlbFormatException::class) fun malformedJsonThrows() {
        GlbMetadataParser.parse(buildGlb("{ this is not json"))
    }

    @Test(expected = GlbFormatException::class) fun jsonRootNotObjectThrows() {
        GlbMetadataParser.parseJson("[1,2,3]")
    }

    @Test(expected = GlbFormatException::class) fun badMagicThrows() {
        val glb = buildGlb("{}")
        glb[0] = 0
        GlbMetadataParser.parse(glb)
    }

    @Test(expected = GlbFormatException::class) fun tooSmallThrows() {
        GlbMetadataParser.parse(ByteArray(5))
    }

    @Test(expected = GlbFormatException::class) fun truncatedChunkThrows() {
        val glb = buildGlb("""{"nodes":[]}""")
        GlbMetadataParser.parse(glb.copyOf(glb.size - 6))
    }

    @Test fun cacheParsesOncePerKey() {
        val glb = buildGlb("""{"nodes":[{"extras":{"prop":"X"}}]}""")
        val first = GlbMetadataCache.getOrParse("modelX", glb)
        val second = GlbMetadataCache.getOrParse("modelX", ByteArray(0)) // would throw if it re-parsed
        assertSame(first, second)
    }
}
