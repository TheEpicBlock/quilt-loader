/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.base.loader;

import com.github.zafarkhaja.semver.Version;
import com.google.gson.*;
import net.fabricmc.api.Side;
import net.fabricmc.base.Fabric;
import net.fabricmc.base.util.SideDeserializer;
import net.fabricmc.base.util.VersionDeserializer;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class Loader {

    public static final Loader INSTANCE = new Loader();

    protected static Logger LOGGER = LogManager.getFormatterLogger("Fabric|Loader");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Side.class, new SideDeserializer())
            .registerTypeAdapter(Version.class, new VersionDeserializer())
            .registerTypeAdapter(ModInfo.Dependency.class, new ModInfo.Dependency.Deserializer())
            .registerTypeAdapter(ModInfo.Person.class, new ModInfo.Person.Deserializer())
            .create();
    private static final JsonParser JSON_PARSER = new JsonParser();

    protected final Map<String, ModContainer> MOD_MAP = new HashMap<>();
    protected List<ModContainer> MODS = new ArrayList<>();

    public Set<String> getClientMixinConfigs() {
        return MODS.stream()
                .map(ModContainer::getInfo)
                .map(ModInfo::getMixins)
                .map(ModInfo.Mixins::getClient)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public Set<String> getCommonMixinConfigs() {
        return MODS.stream()
                .map(ModContainer::getInfo)
                .map(ModInfo::getMixins)
                .map(ModInfo.Mixins::getCommon)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public void load(File modsDir) {
        if (!checkModsDirectory(modsDir)) {
            return;
        }

        List<ModInfo> existingMods = new ArrayList<>();

        int classpathModsCount = 0;
        if (Boolean.parseBoolean(System.getProperty("fabric.development", "false"))) {
            List<ModInfo> classpathMods = getClasspathMods();
            existingMods.addAll(classpathMods);
            classpathModsCount = classpathMods.size();
            LOGGER.debug("Found %d classpath mods", classpathModsCount);
        }

        for (File f : modsDir.listFiles()) {
            if (f.isDirectory()) {
                continue;
            }
            if (!f.getPath().endsWith(".jar")) {
                continue;
            }

            ModInfo[] fileMods = getJarMods(f);

            if (fileMods.length != 0) {
                try {
                    Launch.classLoader.addURL(f.toURI().toURL());
                } catch (MalformedURLException e) {
                    LOGGER.error("Unable to load mod from %s", f.getName());
                    e.printStackTrace();
                    continue;
                }
            }

            Collections.addAll(existingMods, fileMods);
        }

        LOGGER.debug("Found %d jar mods", existingMods.size() - classpathModsCount);

        mods:
        for (ModInfo mod : existingMods) {
            if (mod.isLazilyLoaded()) {
                innerMods:
                for (ModInfo mod2 : existingMods) {
                    if (mod == mod2) {
                        continue innerMods;
                    }
                    for (Map.Entry<String, ModInfo.Dependency> entry : mod2.getDependencies().entrySet()) {
                        String depId = entry.getKey();
                        ModInfo.Dependency dep = entry.getValue();
                        if (depId.equalsIgnoreCase(mod.getGroup() + "." + mod.getId()) && dep.satisfiedBy(mod)) {
                            addMod(mod, true);
                        }
                    }
                }
                continue mods;
            }
            addMod(mod, true);
        }

        LOGGER.info("Loading %d mods: %s", MODS.size(), String.join(", ", MODS.stream()
                .map(ModContainer::getInfo)
                .map(mod -> mod.getGroup() + "." + mod.getId())
                .collect(Collectors.toList())));

        checkDependencies();
        sort();
        initializeMods();
    }

    public boolean isModLoaded(String group, String id) {
        return MOD_MAP.containsKey(group + "." + id);
    }

    public List<ModContainer> getMods() {
        return MODS;
    }

    protected static List<ModInfo> getClasspathMods() {
        List<ModInfo> mods = new ArrayList<>();

        String javaHome = System.getProperty("java.home");
        String modsDir = new File(Fabric.getGameDirectory(), "mods").getAbsolutePath();

        URL[] urls = Launch.classLoader.getURLs();
        for (URL url : urls) {
            if (url.getPath().startsWith(javaHome) || url.getPath().startsWith(modsDir)) {
                continue;
            }

            LOGGER.debug("Attempting to find classpath mods from " + url.getPath());
            File f = new File(url.getFile());
            if (f.exists()) {
                if (f.isDirectory()) {
                    File modJson = new File(f, "mod.json");
                    if (modJson.exists()) {
                        try {
                            Collections.addAll(mods, getMods(new FileInputStream(modJson)));
                        } catch (FileNotFoundException e) {
                            LOGGER.error("Unable to load mod from directory " + f.getPath());
                            e.printStackTrace();
                        }
                    }
                } else if (f.getName().endsWith(".jar")) {
                    Collections.addAll(mods, getJarMods(f));
                }
            }
        }
        return mods;
    }

    protected void addMod(ModInfo info, boolean initialize) {
        Side currentSide = Fabric.getSidedHandler().getSide();
        if ((currentSide == Side.CLIENT && !info.getSide().hasClient()) || (currentSide == Side.SERVER && !info.getSide().hasServer())) {
            return;
        }
        ModContainer container = new ModContainer(info, initialize);
        MODS.add(container);
        MOD_MAP.put(info.getGroup() + "." + info.getId(), container);
    }

    protected void checkDependencies() {
        LOGGER.debug("Validating mod dependencies");

        for (ModContainer mod : MODS) {

            dependencies:
            for (Map.Entry<String, ModInfo.Dependency> entry : mod.getInfo().getDependencies().entrySet()) {
                String depId = entry.getKey();
                ModInfo.Dependency dep = entry.getValue();
                if (dep.isRequired()) {

                    innerMods:
                    for (ModContainer mod2 : MODS) {
                        if (mod == mod2) {
                            continue innerMods;
                        }
                        if (depId.equalsIgnoreCase(mod2.getInfo().getGroup() + "." + mod2.getInfo().getId()) && dep.satisfiedBy(mod2.getInfo())) {
                            continue dependencies;
                        }
                    }
//					TODO: for official modules, query/download from maven
                    throw new DependencyException(String.format("Mod %s.%s requires dependency %s @ %s", mod.getInfo().getGroup(), mod.getInfo().getId(), depId, String.join(", ", dep.getVersionMatchers())));
                }
            }
        }
    }

    private void sort() {
        LOGGER.debug("Sorting mods");

        LinkedList<ModContainer> sorted = new LinkedList<>();
        for (ModContainer mod : MODS) {
            if (sorted.isEmpty() || mod.getInfo().getDependencies().size() == 0) {
                sorted.addFirst(mod);
            } else {
                boolean b = false;
                l1:
                for (int i = 0; i < sorted.size(); i++) {
                    for (Map.Entry<String, ModInfo.Dependency> entry : sorted.get(i).getInfo().getDependencies().entrySet()) {
                        String depId = entry.getKey();
                        ModInfo.Dependency dep = entry.getValue();

                        if (depId.equalsIgnoreCase(mod.getInfo().getGroup() + "." + mod.getInfo().getId()) && dep.satisfiedBy(mod.getInfo())) {
                            sorted.add(i, mod);
                            b = true;
                            break l1;
                        }
                    }
                }

                if (!b) {
                    sorted.addLast(mod);
                }
            }
        }
        MODS = sorted;
    }

    private void initializeMods() {
        Fabric.getLoadingBus().addDummyHookName("fabric:modsInitialized");
        for (ModContainer mod : MODS) {
            if (mod.hasInstance()) {
                mod.initialize();
            }
        }
        Fabric.getLoadingBus().call("fabric:modsInitialized");
    }

    protected static boolean checkModsDirectory(File modsDir) {
        if (!modsDir.exists()) {
            modsDir.mkdirs();
            return false;
        }
        return modsDir.isDirectory();
    }

    protected static ModInfo[] getJarMods(File f) {
        try {
            JarFile jar = new JarFile(f);
            ZipEntry entry = jar.getEntry("mod.json");
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    return getMods(in);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Unable to load mod from %s", f.getName());
            e.printStackTrace();
        }

        return new ModInfo[0];
    }

    protected static ModInfo[] getMods(InputStream in) {
        JsonElement el = JSON_PARSER.parse(new InputStreamReader(in));
        if (el.isJsonObject()) {
            return new ModInfo[]{GSON.fromJson(el, ModInfo.class)};
        } else if (el.isJsonArray()) {
            JsonArray array = el.getAsJsonArray();
            ModInfo[] mods = new ModInfo[array.size()];
            for (int i = 0; i < array.size(); i++) {
                mods[i] = GSON.fromJson(array.get(i), ModInfo.class);
            }
            return mods;
        }

        return new ModInfo[0];
    }

}
