// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.preferences.map;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.data.preferences.sources.ExtendedSourceEntry;
import org.openstreetmap.josm.data.preferences.sources.PresetPrefHelper;
import org.openstreetmap.josm.data.preferences.sources.SourceEntry;
import org.openstreetmap.josm.data.preferences.sources.SourceProvider;
import org.openstreetmap.josm.data.preferences.sources.SourceType;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.help.HelpUtil;
import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceSettingFactory;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane.PreferencePanel;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane.ValidationListener;
import org.openstreetmap.josm.gui.preferences.SourceEditor;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPreset;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetReader;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresets;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Preference settings for tagging presets.
 */
public final class TaggingPresetPreference extends DefaultTabPreferenceSetting {

    private final class TaggingPresetValidationListener implements ValidationListener {
        @Override
        public boolean validatePreferences() {
            if (sources.hasActiveSourcesChanged()) {
                List<Integer> sourcesToRemove = new ArrayList<>();
                int i = -1;
                SOURCES:
                    for (SourceEntry source: sources.getActiveSources()) {
                        i++;
                        boolean canLoad = false;
                        try {
                            TaggingPresetReader.readAll(source.url, false);
                            canLoad = true;
                        } catch (IOException e) {
                            Logging.log(Logging.LEVEL_WARN, tr("Could not read tagging preset source: {0}", source), e);
                            ExtendedDialog ed = new ExtendedDialog(MainApplication.getMainFrame(), tr("Error"),
                                    tr("Yes"), tr("No"), tr("Cancel"));
                            ed.setContent(tr("Could not read tagging preset source: {0}\nDo you want to keep it?", source));
                            switch (ed.showDialog().getValue()) {
                            case 1:
                                continue SOURCES;
                            case 2:
                                sourcesToRemove.add(i);
                                continue SOURCES;
                            default:
                                return false;
                            }
                        } catch (SAXException e) {
                            // We will handle this in step with validation
                            Logging.trace(e);
                        }

                        String errorMessage = null;

                        try {
                            TaggingPresetReader.readAll(source.url, true);
                        } catch (IOException e) {
                            // Should not happen, but at least show message
                            String msg = tr("Could not read tagging preset source: {0}", source);
                            Logging.log(Logging.LEVEL_ERROR, msg, e);
                            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), msg);
                            return false;
                        } catch (SAXParseException e) {
                            if (canLoad) {
                                errorMessage = tr("<html>Tagging preset source {0} can be loaded but it contains errors. " +
                                        "Do you really want to use it?<br><br><table width=600>Error is: [{1}:{2}] {3}</table></html>",
                                        source, e.getLineNumber(), e.getColumnNumber(), Utils.escapeReservedCharactersHTML(e.getMessage()));
                            } else {
                                errorMessage = tr("<html>Unable to parse tagging preset source: {0}. " +
                                        "Do you really want to use it?<br><br><table width=400>Error is: [{1}:{2}] {3}</table></html>",
                                        source, e.getLineNumber(), e.getColumnNumber(), Utils.escapeReservedCharactersHTML(e.getMessage()));
                            }
                            Logging.log(Logging.LEVEL_WARN, errorMessage, e);
                        } catch (SAXException e) {
                            if (canLoad) {
                                errorMessage = tr("<html>Tagging preset source {0} can be loaded but it contains errors. " +
                                        "Do you really want to use it?<br><br><table width=600>Error is: {1}</table></html>",
                                        source, Utils.escapeReservedCharactersHTML(e.getMessage()));
                            } else {
                                errorMessage = tr("<html>Unable to parse tagging preset source: {0}. " +
                                        "Do you really want to use it?<br><br><table width=600>Error is: {1}</table></html>",
                                        source, Utils.escapeReservedCharactersHTML(e.getMessage()));
                            }
                            Logging.log(Logging.LEVEL_ERROR, errorMessage, e);
                        }

                        if (errorMessage != null) {
                            Logging.error(errorMessage);
                            int result = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), new JLabel(errorMessage), tr("Error"),
                                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE);

                            switch (result) {
                            case JOptionPane.YES_OPTION:
                                continue SOURCES;
                            case JOptionPane.NO_OPTION:
                                sourcesToRemove.add(i);
                                continue SOURCES;
                            default:
                                return false;
                            }
                        }
                    }
                sources.removeSources(sourcesToRemove);
            }
            return true;
        }
    }

    /**
     * Factory used to create a new {@code TaggingPresetPreference}.
     */
    public static class Factory implements PreferenceSettingFactory {
        @Override
        public PreferenceSetting createPreferenceSetting() {
            return new TaggingPresetPreference();
        }
    }

    private TaggingPresetPreference() {
        super("dialogs/propertiesdialog", tr("Tagging Presets"), tr("Tagging Presets"));
    }

    private static final List<SourceProvider> presetSourceProviders = new ArrayList<>();

    private SourceEditor sources;
    private JCheckBox useValidator;
    private JCheckBox sortMenu;

    /**
     * Registers a new additional preset source provider.
     * @param provider The preset source provider
     * @return {@code true}, if the provider has been added, {@code false} otherwise
     */
    public static boolean registerSourceProvider(SourceProvider provider) {
        if (provider != null)
            return presetSourceProviders.add(provider);
        return false;
    }

    private final ValidationListener validationListener = new TaggingPresetValidationListener();

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        useValidator = new JCheckBox(tr("Run data validator on user input"), TaggingPreset.USE_VALIDATOR.get());
        sortMenu = new JCheckBox(tr("Sort presets menu alphabetically"), TaggingPresets.SORT_MENU.get());

        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        panel.add(useValidator, GBC.std().insets(5, 5, 0, 0));
        panel.add(new JLabel(ImageProvider.get("dialogs/validator")), GBC.eol().insets(5, 5, 0, 0));
        panel.add(sortMenu, GBC.eol().insets(5, 0, 5, 0));

        sources = new TaggingPresetSourceEditor();
        panel.add(sources, GBC.eol().fill(GBC.BOTH));
        PreferencePanel preferencePanel = gui.createPreferenceTab(this);
        preferencePanel.add(panel, GBC.eol().fill(GBC.BOTH));
        sources.deferLoading(gui, preferencePanel);
        gui.addValidationListener(validationListener);
    }

    public static class TaggingPresetSourceEditor extends SourceEditor {

        public TaggingPresetSourceEditor() {
            super(SourceType.TAGGING_PRESET, Config.getUrls().getJOSMWebsite()+"/presets", presetSourceProviders, true);
        }

        @Override
        public Collection<? extends SourceEntry> getInitialSourcesList() {
            return PresetPrefHelper.INSTANCE.get();
        }

        @Override
        public boolean finish() {
            return doFinish(PresetPrefHelper.INSTANCE, TaggingPresets.ICON_SOURCES.getKey());
        }

        @Override
        public Collection<ExtendedSourceEntry> getDefault() {
            return PresetPrefHelper.INSTANCE.getDefault();
        }

        @Override
        public Collection<String> getInitialIconPathsList() {
            return TaggingPresets.ICON_SOURCES.get();
        }

        @Override
        public String getStr(I18nString ident) {
            switch (ident) {
            case AVAILABLE_SOURCES:
                return tr("Available presets:");
            case ACTIVE_SOURCES:
                return tr("Active presets:");
            case NEW_SOURCE_ENTRY_TOOLTIP:
                return tr("Add a new preset by entering filename or URL");
            case NEW_SOURCE_ENTRY:
                return tr("New preset entry:");
            case REMOVE_SOURCE_TOOLTIP:
                return tr("Remove the selected presets from the list of active presets");
            case EDIT_SOURCE_TOOLTIP:
                return tr("Edit the filename or URL for the selected active preset");
            case ACTIVATE_TOOLTIP:
                return tr("Add the selected available presets to the list of active presets");
            case RELOAD_ALL_AVAILABLE:
                return marktr("Reloads the list of available presets from ''{0}''");
            case LOADING_SOURCES_FROM:
                return marktr("Loading preset sources from ''{0}''");
            case FAILED_TO_LOAD_SOURCES_FROM:
                return marktr("<html>Failed to load the list of preset sources from<br>"
                        + "''{0}''.<br>"
                        + "<br>"
                        + "Details (untranslated):<br>{1}</html>");
            case FAILED_TO_LOAD_SOURCES_FROM_HELP_TOPIC:
                return "/Preferences/Presets#FailedToLoadPresetSources";
            case ILLEGAL_FORMAT_OF_ENTRY:
                return marktr("Warning: illegal format of entry in preset list ''{0}''. Got ''{1}''");
            default: throw new AssertionError();
            }
        }
    }

    @Override
    public boolean ok() {
        TaggingPreset.USE_VALIDATOR.put(useValidator.isSelected());
        if (sources.finish() || TaggingPresets.SORT_MENU.put(sortMenu.isSelected())) {
            TaggingPresets.destroy();
            TaggingPresets.initialize();
        }

        return false;
    }

    @Override
    public boolean isExpert() {
        return false;
    }

    @Override
    public String getHelpContext() {
        return HelpUtil.ht("/Preferences/TaggingPresetPreference");
    }
}
