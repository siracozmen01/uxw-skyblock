package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code /is reload} reads, and what it refuses to touch.
 *
 * <p>The specification draws the line: the message catalogues and the menu specifications, and
 * nothing else. Core service registrations, Bukkit listeners, connection pools and database tables
 * are never torn down or re-bound, because hot swapping a subsystem is how a plugin leaks
 * classloaders, leaves listeners behind and desynchronises schedulers.
 */
class SkyblockReloaderTest {

    @TempDir
    Path dataDir;

    private Path writeCatalogue(String locale, String noIslandLine) throws IOException {
        Path messages = Files.createDirectories(dataDir.resolve("messages"));
        Path file = messages.resolve("messages_" + locale + ".conf");
        Files.writeString(
                file, "prefix = \"\"\nerror {\n    no_island = \"" + noIslandLine + "\"\n}\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("A line the operator changed on disk is the line a player reads afterwards")
    void aChangedLineIsReadBack() throws IOException {
        writeCatalogue("en", "before");
        MessageProvider provider = new MessageProvider("en");
        provider.loadFromFile("en", dataDir.resolve("messages/messages_en.conf"));
        assertThat(provider.getRaw("error.no_island", "en")).isEqualTo("before");

        writeCatalogue("en", "after");
        SkyblockReloader.ReloadReport report = new SkyblockReloader(provider, dataDir, null).reload();

        assertThat(report.clean()).isTrue();
        assertThat(provider.getRaw("error.no_island", "en")).isEqualTo("after");
    }

    @Test
    @DisplayName("Every catalogue the operator has is read, whatever languages they are")
    void everyCatalogueIsRead() throws IOException {
        writeCatalogue("en", "one");
        writeCatalogue("tr", "iki");
        writeCatalogue("az", "uc");

        SkyblockReloader.ReloadReport report = new SkyblockReloader(new MessageProvider("en"), dataDir, null).reload();

        assertThat(report.catalogues())
                .describedAs("the number of languages is the number of files, and nothing counts them")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("A catalogue that will not parse is named, and every other one is still read")
    void abrokenCatalogueDoesNotStopTheRest() throws IOException {
        writeCatalogue("en", "fine");
        Files.writeString(
                dataDir.resolve("messages/messages_tr.conf"), "this { is not = valid hocon \"", StandardCharsets.UTF_8);

        MessageProvider provider = new MessageProvider("en");
        SkyblockReloader.ReloadReport report = new SkyblockReloader(provider, dataDir, null).reload();

        assertThat(report.clean()).isFalse();
        assertThat(report.failures()).hasSize(1).first().asString().contains("messages_tr.conf");
        assertThat(provider.getRaw("error.no_island", "en"))
                .describedAs("one broken file must not take the rest of a server with it")
                .isEqualTo("fine");
    }

    @Test
    @DisplayName("A file that is not a catalogue is not read as one")
    void otherFilesAreLeftAlone() throws IOException {
        writeCatalogue("en", "fine");
        Files.writeString(dataDir.resolve("messages/notes.txt"), "just a note", StandardCharsets.UTF_8);

        SkyblockReloader.ReloadReport report = new SkyblockReloader(new MessageProvider("en"), dataDir, null).reload();

        assertThat(report.catalogues()).isEqualTo(1);
        assertThat(report.clean()).isTrue();
    }

    @Test
    @DisplayName("A server with no messages folder reloads cleanly rather than failing")
    void noMessagesFolderIsFine() {
        SkyblockReloader.ReloadReport report = new SkyblockReloader(new MessageProvider("en"), dataDir, null).reload();

        assertThat(report.clean()).isTrue();
        assertThat(report.catalogues()).isZero();
    }

    @Test
    @DisplayName("A reload with no menu engine reads no menu rather than pretending it did")
    void noMenuEngineReadsNoMenu() {
        SkyblockReloader.ReloadReport report = new SkyblockReloader(new MessageProvider("en"), dataDir, null).reload();

        assertThat(report.menus()).isZero();
    }

    @Test
    @DisplayName("The bundled catalogues answer a key the operator's own file does not carry")
    void theBundledCataloguesStillAnswer() throws IOException {
        writeCatalogue("en", "mine");

        MessageProvider provider = new MessageProvider("en");
        new SkyblockReloader(provider, dataDir, null).reload();

        assertThat(provider.getRaw("error.no_island", "en"))
                .describedAs("the operator's own line wins")
                .isEqualTo("mine");
        assertThat(provider.getRaw("error.players_only", "en"))
                .describedAs("a key the operator's file does not carry still answers")
                .isNotEqualTo("error.players_only");
    }
}
