package com.example.webboard.content.train;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TrainMetadataStorageTest — exercises CRUD for categories/lines/tags/metadata + persistence.
 *
 * <p>Uses a {@link TempDir} so each test gets a fresh file; no shared state between tests.
 * Mirrors the patterns from {@code BoardRegistryTest} (sync CRUD + listener invariants).
 */
class TrainMetadataStorageTest {

    @TempDir
    Path tmpDir;
    private TrainMetadataStorage storage;

    @BeforeEach
    void setup() {
        storage = new TrainMetadataStorage(tmpDir.resolve("trains.json"));
        storage.init();
    }

    // ---------- categories ----------

    @Test
    void empty_storage_returnsEmptyLists() {
        assertTrue(storage.allCategories().isEmpty());
        assertTrue(storage.allLines().isEmpty());
        assertTrue(storage.allStationTags().isEmpty());
        assertTrue(storage.allTrainMetadata().isEmpty());
    }

    @Test
    void createCategory_assignsUniqueId_andPersists() {
        TrainCategory c1 = storage.createCategory("Freight", 0xFF8800, TrainCategory.FREIGHT);
        TrainCategory c2 = storage.createCategory("Passenger", 0x0088FF, TrainCategory.PASSENGER);
        assertEquals(2, storage.allCategories().size());
        assertNotEquals(c1.id(), c2.id());
        assertEquals(TrainCategory.FREIGHT, c1.freightType());
        assertEquals(TrainCategory.PASSENGER, c2.freightType());
    }

    @Test
    void updateCategory_replacesFields() {
        TrainCategory c = storage.createCategory("Old", 0x000000, TrainCategory.OTHER);
        TrainCategory updated = storage.updateCategory(c.id(), "New", 0xFFFFFF, TrainCategory.FREIGHT);
        assertNotNull(updated);
        assertEquals("New", updated.name());
        assertEquals(0xFFFFFF, updated.color());
        assertEquals(TrainCategory.FREIGHT, updated.freightType());
        // Refetched from store reflects the update
        assertEquals("New", storage.getCategory(c.id()).name());
    }

    @Test
    void updateCategory_missingId_returnsNull() {
        assertNull(storage.updateCategory("cat-nope", "X", 0, TrainCategory.OTHER));
    }

    @Test
    void deleteCategory_removesIt_andReturnsTrue() {
        TrainCategory c = storage.createCategory("Tmp", 0, TrainCategory.OTHER);
        assertTrue(storage.deleteCategory(c.id()));
        assertNull(storage.getCategory(c.id()));
        assertFalse(storage.deleteCategory(c.id())); // second delete is false
    }

    @Test
    void createCategory_normalizesFreightType() {
        TrainCategory c = storage.createCategory("X", 0, "garbage");
        assertEquals(TrainCategory.OTHER, c.freightType());
    }

    // ---------- lines ----------

    @Test
    void createLine_storesOrderedStations() {
        TrainLine l = storage.createLine("Main", null, 0xFF0000, List.of("A", "B", "C"));
        assertEquals(3, l.stationCount());
        assertTrue(l.serves("B"));
        assertFalse(l.serves("Z"));
        assertEquals("Main", storage.getLine(l.id()).name());
    }

    @Test
    void updateLine_changesStations() {
        TrainLine l = storage.createLine("L", null, 0, List.of("A"));
        TrainLine updated = storage.updateLine(l.id(), "L2", null, 0, List.of("A", "B", "C"));
        assertEquals(3, updated.stationCount());
    }

    @Test
    void deleteLine_works() {
        TrainLine l = storage.createLine("L", null, 0, List.of());
        assertTrue(storage.deleteLine(l.id()));
        assertNull(storage.getLine(l.id()));
    }

    // ---------- station tags ----------

    @Test
    void createTag_andDelete() {
        StationTag t = storage.createStationTag("Interchange", "terminal", 0x00FF00);
        assertEquals("Interchange", t.name());
        assertEquals("terminal", t.type());
        assertTrue(storage.deleteStationTag(t.id()));
        assertNull(storage.getStationTag(t.id()));
    }

    @Test
    void createTag_nullTypeBecomesEmpty() {
        StationTag t = storage.createStationTag("X", null, 0);
        assertEquals("", t.type());
    }

    // ---------- train metadata ----------

    @Test
    void upsertMetadata_createsIfMissing() {
        TrainMetadata m = TrainMetadata.create("train-1", "Thomas");
        storage.upsertMetadata(m);
        assertEquals(1, storage.allTrainMetadata().size());
        assertEquals("Thomas", storage.getMetadata("train-1").trainName());
    }

    @Test
    void upsertMetadata_replacesExisting() {
        storage.upsertMetadata(TrainMetadata.create("t1", "Old"));
        storage.upsertMetadata(new TrainMetadata("t1", "New", null, null,
                TrainMetadata.DEFAULT_COLOR, "notes", System.currentTimeMillis()));
        assertEquals(1, storage.allTrainMetadata().size());
        assertEquals("New", storage.getMetadata("t1").trainName());
        assertEquals("notes", storage.getMetadata("t1").notes());
    }

    @Test
    void deleteMetadata_returnsFalseWhenMissing() {
        assertFalse(storage.deleteMetadata("nope"));
    }

    // ---------- persistence ----------

    @Test
    void reinit_readsBackSavedState() {
        TrainCategory c = storage.createCategory("Freight", 0xFF8800, TrainCategory.FREIGHT);
        TrainLine l = storage.createLine("Main", c.id(), 0xFF0000, List.of("A", "B"));
        StationTag t = storage.createStationTag("Terminal", "terminal", 0x00FF00);
        storage.upsertMetadata(TrainMetadata.create("t1", "Thomas"));
        storage.close();

        TrainMetadataStorage reloaded = new TrainMetadataStorage(tmpDir.resolve("trains.json"));
        reloaded.init();
        assertEquals(1, reloaded.allCategories().size());
        assertEquals("Freight", reloaded.getCategory(c.id()).name());
        assertEquals(1, reloaded.allLines().size());
        assertEquals(2, reloaded.getLine(l.id()).stationCount());
        assertEquals(1, reloaded.allStationTags().size());
        assertEquals("Terminal", reloaded.getStationTag(t.id()).name());
        assertEquals(1, reloaded.allTrainMetadata().size());
        assertEquals("Thomas", reloaded.getMetadata("t1").trainName());
    }

    @Test
    void reinit_onMissingFile_startsEmpty() {
        storage.close();
        TrainMetadataStorage fresh = new TrainMetadataStorage(tmpDir.resolve("does-not-exist.json"));
        fresh.init();
        assertTrue(fresh.allCategories().isEmpty());
        assertTrue(fresh.allLines().isEmpty());
    }

    // ---------- id uniqueness across reinit ----------

    @Test
    void idsRemainUnique_afterReload() {
        TrainCategory c1 = storage.createCategory("A", 0, TrainCategory.OTHER);
        storage.close();
        TrainMetadataStorage reloaded = new TrainMetadataStorage(tmpDir.resolve("trains.json"));
        reloaded.init();
        TrainCategory c2 = reloaded.createCategory("B", 0, TrainCategory.OTHER);
        assertNotEquals(c1.id(), c2.id());
    }
}
