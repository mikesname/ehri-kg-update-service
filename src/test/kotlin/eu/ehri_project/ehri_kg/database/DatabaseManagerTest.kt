package eu.ehri_project.ehri_kg.database

import eu.ehri_project.ehri_kg.model.EHRIEvent
import eu.ehri_project.ehri_kg.model.EHRIUpdateReport
import eu.ehri_project.ehri_kg.support.testConfig
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseManagerTest {

    @TempDir
    lateinit var dir: File

    private val event = EHRIEvent("evt-1", "update-event", "2026-01-01T00:00:00Z", "gb", "Country")

    private fun report(event: EHRIEvent, errors: String = "", timeStamp: String) =
        EHRIUpdateReport(event, listOf("INSERT DATA {}"), listOf("Added: []"), errors, timeStamp)

    @Test
    fun `creates the database file on construction`() {
        DatabaseManager(testConfig(dir))

        assertTrue(File(dir, "eventsHistory.db").isFile)
    }

    private val dbFile get() = File(dir, "eventsHistory.db")

    @Test
    fun `a database file without the table is repaired`() {
        dbFile.createNewFile()

        val database = DatabaseManager(testConfig(dir))
        database.insertReport(report(event, timeStamp = "2026-01-01T00:00:01Z"))

        assertTrue(database.checkIfSuccessfullyProcessed(event))
    }

    @Test
    fun `reopening an existing database keeps its history`() {
        DatabaseManager(testConfig(dir)).insertReport(report(event, timeStamp = "2026-01-01T00:00:01Z"))

        assertTrue(DatabaseManager(testConfig(dir)).checkIfSuccessfullyProcessed(event))
    }

    @Test
    fun `an event recorded without errors is processed`() {
        val database = DatabaseManager(testConfig(dir))
        database.insertReport(report(event, timeStamp = "2026-01-01T00:00:01Z"))

        assertTrue(database.checkIfSuccessfullyProcessed(event))
    }

    @Test
    fun `an event recorded with errors is not processed`() {
        val database = DatabaseManager(testConfig(dir))
        database.insertReport(report(event, errors = "boom", timeStamp = "2026-01-01T00:00:01Z"))

        assertFalse(database.checkIfSuccessfullyProcessed(event))
    }

    @Test
    fun `the latest attempt decides the outcome`() {
        val database = DatabaseManager(testConfig(dir))
        database.insertReport(report(event, errors = "oh dear", timeStamp = "2026-01-01T00:00:01Z"))
        database.insertReport(report(event, timeStamp = "2026-01-01T00:00:02Z"))
        assertTrue(database.checkIfSuccessfullyProcessed(event))

        database.insertReport(report(event, errors = "oh dear again", timeStamp = "2026-01-01T00:00:03Z"))
        assertFalse(database.checkIfSuccessfullyProcessed(event))
    }

    @Test
    fun `matching requires the same event id, item and event type`() {
        val database = DatabaseManager(testConfig(dir))
        database.insertReport(report(event, timeStamp = "2026-01-01T00:00:01Z"))

        assertFalse(database.checkIfSuccessfullyProcessed(event.copy(eventId = "evt-2")))
        assertFalse(database.checkIfSuccessfullyProcessed(event.copy(id = "nl")))
        assertFalse(database.checkIfSuccessfullyProcessed(event.copy(type = "Repository")))
        assertFalse(database.checkIfSuccessfullyProcessed(event.copy(eventType = "delete-event")))
    }

    private fun DatabaseManager.record(eventId: String, itemId: String = "gb", errors: String = "") =
        insertReport(report(event.copy(eventId = eventId, id = itemId), errors, timeStamp = "2026-01-01T00:00:00Z"))

    @Test
    fun `there is nothing to resume from without history`() {
        assertNull(DatabaseManager(testConfig(dir)).resumeEventId())
    }

    @Test
    fun `resumes after the last event when nothing failed`() {
        val database = DatabaseManager(testConfig(dir))
        database.record("evt-1")
        database.record("evt-2", itemId = "gb")
        database.record("evt-2", itemId = "nl")

        assertEquals("evt-2", database.resumeEventId())
    }

    @Test
    fun `resumes before the first failed event so that it is replayed`() {
        val database = DatabaseManager(testConfig(dir))
        database.record("evt-1")
        database.record("evt-2", errors = "boom")
        database.record("evt-3")

        assertEquals("evt-1", database.resumeEventId())
    }

    @Test
    fun `a failure in any item of an event replays the whole event`() {
        val database = DatabaseManager(testConfig(dir))
        database.record("evt-1")
        database.record("evt-2", itemId = "gb")
        database.record("evt-2", itemId = "nl", errors = "boom")

        assertEquals("evt-1", database.resumeEventId())
    }

    @Test
    fun `failures that were later retried successfully are resolved`() {
        val database = DatabaseManager(testConfig(dir))
        database.record("evt-1")
        database.record("evt-2", errors = "boom")
        database.record("evt-3")
        database.record("evt-2")

        assertEquals("evt-3", database.resumeEventId())
    }

    @Test
    fun `there is nothing to resume from when the first event failed`() {
        val database = DatabaseManager(testConfig(dir))
        database.record("evt-1", errors = "boom")
        database.record("evt-2")

        assertNull(database.resumeEventId())
    }
}
