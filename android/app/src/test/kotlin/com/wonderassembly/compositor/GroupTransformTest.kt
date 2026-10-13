package com.wonderassembly.compositor

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupTransformTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun fixture(body: (GroupProjectFixture) -> Unit) {
        val fixture = GroupProjectFixture.create(context)
        try { body(fixture) } finally { fixture.project.releasePrivateAssets() }
    }

    @Test fun visibleNestedMembersAndRotatedBoundsMatchSourceRules() = fixture { f ->
        val editor = ProjectLayerTransformEditor(context)
        val second = f.project.layers.single { it.id == f.second }
        val changed = editor.update(f.project, f.second, second.transform.copy(rotation = 90.0))
        val plan = GroupTransformPlan.forGroup(changed, f.root)
        assertEquals(setOf(f.first, f.second), plan.originals.keys)
        assertEquals(0.0, plan.box.x, 1e-10); assertEquals(-5.0, plan.box.y, 1e-10)
        assertEquals(55.0, plan.box.width, 1e-10); assertEquals(20.0, plan.box.height, 1e-10)
        val zeroOpacity = ProjectLayerMetadataEditor(context).update(changed, f.second, opacity = 0.0)
        assertEquals(plan, GroupTransformPlan.forGroup(zeroOpacity, f.root))
        val hiddenRoot = ProjectLayerMetadataEditor(context).update(changed, f.root, visible = false)
        assertThrows(IllegalArgumentException::class.java) { GroupTransformPlan.forGroup(hiddenRoot, f.root) }
        assertThrows(IllegalArgumentException::class.java) { GroupTransformPlan.forGroup(changed, f.first) }
    }

    @Test fun batchTranslationMovesLinkedMasksAndPreservesFolderHiddenAndAssets() = fixture { f ->
        val base = f.project; val bytes = base.originalManifestBytes.copyOf()
        val plan = GroupTransformPlan.forGroup(base, f.root)
        val changed = ProjectLayerTransformEditor(context).updateGroup(base, f.root, plan.box.copy(x = 12.25, y = -7.5))
        for (id in plan.originals.keys) {
            val before = base.layers.single { it.id == id }; val after = changed.layers.single { it.id == id }
            assertEquals(before.transform.x + 12.25, after.transform.x, 0.0)
            assertEquals(before.transform.y - 7.5, after.transform.y, 0.0)
        }
        assertNull(changed.layers.single { it.id == f.first }.maskTransform)
        assertEquals(base.layers.single { it.id == f.second }.transform, changed.layers.single { it.id == f.second }.maskTransform)
        for (id in listOf(f.root, f.nested, f.hidden, f.hiddenChild, f.outside))
            assertEquals(base.layers.single { it.id == id }, changed.layers.single { it.id == id })
        assertEquals(base.packageAssets, changed.packageAssets)
        assertArrayEquals(bytes, base.originalManifestBytes)
        val records = changed.originalManifest.getJSONArray("layers")
        for (i in 0 until records.length()) {
            assertEquals("kept", records.getJSONObject(i).getString("futureLayerField"))
            assertEquals(42, records.getJSONObject(i).getJSONObject("transform").getInt("futureTransformField"))
        }
        val manifest = org.json.JSONObject(String(base.originalManifestBytes, Charsets.UTF_8))
        val layerRecords = manifest.getJSONArray("layers")
        val firstRecord = (0 until layerRecords.length()).map { layerRecords.getJSONObject(it) }.single { it.getString("id") == f.first }
        val placement = org.json.JSONObject(firstRecord.getJSONObject("transform").toString()).put("futureMaskField", 88)
        placement.getJSONObject("origin").put("x", 4.0).put("y", 2.0)
        firstRecord.put("maskPlacement", placement)
        val placed = CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
            manifest.toString().toByteArray(Charsets.UTF_8), base.packageAssets, base.archiveCacheDirectory)
        val moved = ProjectLayerTransformEditor(context).updateGroup(placed, f.root, plan.box.copy(x = 12.25, y = -7.5))
        assertEquals(16.25, moved.layers.single { it.id == f.first }.maskTransform!!.x, 0.0)
        assertEquals(-5.5, moved.layers.single { it.id == f.first }.maskTransform!!.y, 0.0)
        val movedRecords = moved.originalManifest.getJSONArray("layers")
        assertEquals(88, (0 until movedRecords.length()).map { movedRecords.getJSONObject(it) }
            .single { it.getString("id") == f.first }.getJSONObject("maskPlacement").getInt("futureMaskField"))
    }

    @Test fun rotationReflectionAndShearProjectionHaveIndependentGeometryGoldens() = fixture { f ->
        val plan = GroupTransformPlan.forGroup(f.project, f.root)
        val rotated = plan.moved(plan.box.copy(rotation = 90.0))
        assertEquals(20.0, rotated.getValue(f.first).x, 1e-10)
        assertEquals(-20.0, rotated.getValue(f.first).y, 1e-10)
        assertEquals(20.0, rotated.getValue(f.second).y, 1e-10)
        assertEquals(90.0, rotated.getValue(f.first).rotation, 1e-10)
        val reflected = plan.moved(plan.box.copy(flipX = true)).getValue(f.first)
        assertEquals(40.0, reflected.x, 1e-10); assertTrue(reflected.flipY); assertFalse(reflected.flipX)
        val box = plan.box.copy(width = 100.0, height = 100.0)
        val original = plan.originals.getValue(f.first).copy(rotation = 45.0)
        val scaled = GroupTransformPlan(box, mapOf(f.first to original)).moved(box.copy(width = 200.0)).getValue(f.first)
        assertEquals(20 * kotlin.math.sqrt(2.5), scaled.width, 1e-10)
        assertEquals(20 / kotlin.math.sqrt(2.5), scaled.height, 1e-10)
        assertEquals(Math.toDegrees(kotlin.math.atan(0.5)), scaled.rotation, 1e-10)
    }

    @Test fun invalidMemberRejectsWholeBatchAndUnchangedTargetIsNoOp() = fixture { f ->
        val editor = ProjectLayerTransformEditor(context)
        val plan = GroupTransformPlan.forGroup(f.project, f.root)
        assertSame(f.project, editor.updateGroup(f.project, f.root, plan.box))
        val first = f.project.layers.single { it.id == f.first }
        val narrow = editor.update(f.project, f.first, first.transform.copy(width = 1.0))
        val old = narrow.originalManifestBytes.copyOf()
        val bounds = GroupTransformPlan.forGroup(narrow, f.root).box
        assertThrows(IllegalArgumentException::class.java) { editor.updateGroup(narrow, f.root, bounds.copy(width = 1.0)) }
        assertArrayEquals(old, narrow.originalManifestBytes)
        val largeAngle = editor.update(f.project, f.first, first.transform.copy(rotation = 350.0))
        val turned = editor.updateGroup(largeAngle, f.root, GroupTransformPlan.forGroup(largeAngle, f.root).box.copy(rotation = 90.0))
        assertEquals(440.0, turned.layers.single { it.id == f.first }.transform.rotation, 1e-10)
    }

    @Test fun fractionalGroupBasisScaleAndProductionArchivePreserveAllFieldsAndPixels() = fixture { f ->
        val editor = ProjectLayerTransformEditor(context)
        val first = f.project.layers.single { it.id == f.first }
        val base = editor.update(f.project, f.first, first.transform.copy(x = -0.25))
        val plan = GroupTransformPlan.forGroup(base, f.root)
        val target = NumericLayerTransform.scale(plan.box, plan.box.width, plan.box.height, 150.5)
        assertEquals(plan.box.x + plan.box.width / 2, target.x + target.width / 2, 1e-10)
        val changed = editor.updateGroup(base, f.root, target)
        val zip = java.io.File.createTempFile("group-transform-", ".zip", context.cacheDir)
        try {
            zip.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, changed, emptyMap()) }
            val reopened = CompProjectArchiveImporter(context, CompProjectReader(context)).read(android.net.Uri.fromFile(zip)) { false }
            try {
                assertEquals(changed.layers, reopened.layers); assertArrayEquals(changed.originalManifestBytes, reopened.originalManifestBytes)
                for ((name, uri) in base.imageUris + base.maskUris)
                    assertArrayEquals(java.io.File(uri.path!!).readBytes(), java.io.File((reopened.imageUris + reopened.maskUris).getValue(name).path!!).readBytes())
            } finally { reopened.releasePrivateAssets() }
        } finally { zip.delete() }
    }
}
