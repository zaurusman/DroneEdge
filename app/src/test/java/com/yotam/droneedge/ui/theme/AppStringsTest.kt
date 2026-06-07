package com.yotam.droneedge.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsTest {

    @Test
    fun `Hebrew instance has no blank String fields`() {
        val s = AppStrings.Hebrew
        assertFalse("code",               s.code.isBlank())
        assertFalse("selectModel",        s.selectModel.isBlank())
        assertFalse("confirm",            s.confirm.isBlank())
        assertFalse("externalModelDesc",  s.externalModelDesc.isBlank())
        assertFalse("externalModelHint",  s.externalModelHint.isBlank())
        assertFalse("statusLabel",        s.statusLabel.isBlank())
        assertFalse("stateIdle",          s.stateIdle.isBlank())
        assertFalse("stateRunning",       s.stateRunning.isBlank())
        assertFalse("stateStopping",      s.stateStopping.isBlank())
        assertFalse("sourceNoSource",     s.sourceNoSource.isBlank())
        assertFalse("sourceCamera",       s.sourceCamera.isBlank())
        assertFalse("sourceFile",         s.sourceFile.isBlank())
        assertFalse("gallery",            s.gallery.isBlank())
        assertFalse("start",              s.start.isBlank())
        assertFalse("stop",               s.stop.isBlank())
        assertFalse("rec",                s.rec.isBlank())
        assertFalse("saving",             s.saving.isBlank())
        assertFalse("dismiss",            s.dismiss.isBlank())
        assertFalse("savedTo",            s.savedTo.isBlank())
        assertFalse("nameRecordingTitle", s.nameRecordingTitle.isBlank())
        assertFalse("sessionNameLabel",   s.sessionNameLabel.isBlank())
        assertFalse("save",               s.save.isBlank())
        assertFalse("skip",               s.skip.isBlank())
        assertFalse("selectSource",       s.selectSource.isBlank())
        assertFalse("sourceCameraBack",   s.sourceCameraBack.isBlank())
        assertFalse("sourceVideoFile",    s.sourceVideoFile.isBlank())
        assertFalse("sourceDji",          s.sourceDji.isBlank())
        assertFalse("sourceFake",         s.sourceFake.isBlank())
        assertFalse("galleryTitle",       s.galleryTitle.isBlank())
        assertFalse("noRecordings",       s.noRecordings.isBlank())
        assertFalse("rename",             s.rename.isBlank())
        assertFalse("delete",             s.delete.isBlank())
        assertFalse("renameSession",      s.renameSession.isBlank())
        assertFalse("deleteSession",      s.deleteSession.isBlank())
        assertFalse("cancel",             s.cancel.isBlank())
        assertFalse("error",              s.error.isBlank())
        assertFalse("ok",                 s.ok.isBlank())
        assertFalse("cameraDenied",       s.cameraDenied.isBlank())
        assertFalse("noUvcCamera",        s.noUvcCamera.isBlank())
        assertFalse("noDjiGoggles",       s.noDjiGoggles.isBlank())
        assertFalse("djiDisconnected",    s.djiDisconnected.isBlank())
        assertFalse("usbDisconnected",    s.usbDisconnected.isBlank())
        // lambdas
        assertTrue(s.modelNotFound("x.tflite").isNotBlank())
        assertTrue(s.deleteConfirmBody("My Session").isNotBlank())
    }

    @Test
    fun `English instance has no blank String fields`() {
        val s = AppStrings.English
        assertFalse("code",               s.code.isBlank())
        assertFalse("selectModel",        s.selectModel.isBlank())
        assertFalse("confirm",            s.confirm.isBlank())
        assertFalse("externalModelDesc",  s.externalModelDesc.isBlank())
        assertFalse("externalModelHint",  s.externalModelHint.isBlank())
        assertFalse("statusLabel",        s.statusLabel.isBlank())
        assertFalse("stateIdle",          s.stateIdle.isBlank())
        assertFalse("stateRunning",       s.stateRunning.isBlank())
        assertFalse("stateStopping",      s.stateStopping.isBlank())
        assertFalse("sourceNoSource",     s.sourceNoSource.isBlank())
        assertFalse("sourceCamera",       s.sourceCamera.isBlank())
        assertFalse("sourceFile",         s.sourceFile.isBlank())
        assertFalse("gallery",            s.gallery.isBlank())
        assertFalse("start",              s.start.isBlank())
        assertFalse("stop",               s.stop.isBlank())
        assertFalse("rec",                s.rec.isBlank())
        assertFalse("saving",             s.saving.isBlank())
        assertFalse("dismiss",            s.dismiss.isBlank())
        assertFalse("savedTo",            s.savedTo.isBlank())
        assertFalse("nameRecordingTitle", s.nameRecordingTitle.isBlank())
        assertFalse("sessionNameLabel",   s.sessionNameLabel.isBlank())
        assertFalse("save",               s.save.isBlank())
        assertFalse("skip",               s.skip.isBlank())
        assertFalse("selectSource",       s.selectSource.isBlank())
        assertFalse("sourceCameraBack",   s.sourceCameraBack.isBlank())
        assertFalse("sourceVideoFile",    s.sourceVideoFile.isBlank())
        assertFalse("sourceDji",          s.sourceDji.isBlank())
        assertFalse("sourceFake",         s.sourceFake.isBlank())
        assertFalse("galleryTitle",       s.galleryTitle.isBlank())
        assertFalse("noRecordings",       s.noRecordings.isBlank())
        assertFalse("rename",             s.rename.isBlank())
        assertFalse("delete",             s.delete.isBlank())
        assertFalse("renameSession",      s.renameSession.isBlank())
        assertFalse("deleteSession",      s.deleteSession.isBlank())
        assertFalse("cancel",             s.cancel.isBlank())
        assertFalse("error",              s.error.isBlank())
        assertFalse("ok",                 s.ok.isBlank())
        assertFalse("cameraDenied",       s.cameraDenied.isBlank())
        assertFalse("noUvcCamera",        s.noUvcCamera.isBlank())
        assertFalse("noDjiGoggles",       s.noDjiGoggles.isBlank())
        assertFalse("djiDisconnected",    s.djiDisconnected.isBlank())
        assertFalse("usbDisconnected",    s.usbDisconnected.isBlank())
        assertTrue(s.modelNotFound("x.tflite").isNotBlank())
        assertTrue(s.deleteConfirmBody("My Session").isNotBlank())
    }

    @Test
    fun `Hebrew and English have different strings`() {
        assertNotEquals(AppStrings.Hebrew.selectModel, AppStrings.English.selectModel)
        assertNotEquals(AppStrings.Hebrew.confirm,     AppStrings.English.confirm)
        assertNotEquals(AppStrings.Hebrew.start,       AppStrings.English.start)
    }

    @Test
    fun `code field identifies each instance`() {
        assert(AppStrings.Hebrew.code  == "HE")
        assert(AppStrings.English.code == "EN")
    }
}
