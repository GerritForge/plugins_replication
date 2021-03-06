// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.replication;

import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isGerrit;
import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isGerritHttp;
import static com.googlesource.gerrit.plugins.replication.AdminApiFactory.isSSH;
import static com.googlesource.gerrit.plugins.replication.ReplicationQueue.repLog;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;

@Singleton
public class ReplicationFileBasedConfig implements ReplicationConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private List<Destination> destinations;
  private final SitePaths site;
  private Path cfgPath;
  private boolean replicateAllOnPluginStart;
  private boolean defaultForceUpdate;
  private int maxRefsToLog;
  private final FileBasedConfig config;
  private final Path pluginDataDir;

  @Inject
  public ReplicationFileBasedConfig(
      SitePaths site, Destination.Factory destinationFactory, @PluginData Path pluginDataDir)
      throws ConfigInvalidException, IOException {
    this.site = site;
    this.cfgPath = site.etc_dir.resolve("replication.config");
    this.config = new FileBasedConfig(cfgPath.toFile(), FS.DETECTED);
    this.destinations = allDestinations(destinationFactory);
    this.pluginDataDir = pluginDataDir;
  }

  /*
   * (non-Javadoc)
   * @see
   * com.googlesource.gerrit.plugins.replication.ReplicationConfig#getDestinations
   * (com.googlesource.gerrit.plugins.replication.ReplicationConfig.FilterType)
   */
  @Override
  public List<Destination> getDestinations(FilterType filterType) {
    Predicate<? super Destination> filter;
    switch (filterType) {
      case PROJECT_CREATION:
        filter = dest -> dest.isCreateMissingRepos();
        break;
      case PROJECT_DELETION:
        filter = dest -> dest.isReplicateProjectDeletions();
        break;
      case ALL:
      default:
        filter = dest -> true;
        break;
    }
    return destinations.stream().filter(Objects::nonNull).filter(filter).collect(toList());
  }

  private List<Destination> allDestinations(Destination.Factory destinationFactory)
      throws ConfigInvalidException, IOException {
    if (!config.getFile().exists()) {
      logger.atWarning().log("Config file %s does not exist; not replicating", config.getFile());
      return Collections.emptyList();
    }
    if (config.getFile().length() == 0) {
      logger.atInfo().log("Config file %s is empty; not replicating", config.getFile());
      return Collections.emptyList();
    }

    try {
      config.load();
    } catch (ConfigInvalidException e) {
      throw new ConfigInvalidException(
          String.format("Config file %s is invalid: %s", config.getFile(), e.getMessage()), e);
    } catch (IOException e) {
      throw new IOException(
          String.format("Cannot read %s: %s", config.getFile(), e.getMessage()), e);
    }

    replicateAllOnPluginStart = config.getBoolean("gerrit", "replicateOnStartup", false);

    defaultForceUpdate = config.getBoolean("gerrit", "defaultForceUpdate", false);

    maxRefsToLog = config.getInt("gerrit", "maxRefsToLog", 0);

    ImmutableList.Builder<Destination> dest = ImmutableList.builder();
    for (RemoteConfig c : allRemotes(config)) {
      if (c.getURIs().isEmpty()) {
        continue;
      }

      // If destination for push is not set assume equal to source.
      for (RefSpec ref : c.getPushRefSpecs()) {
        if (ref.getDestination() == null) {
          ref.setDestination(ref.getSource());
        }
      }

      if (c.getPushRefSpecs().isEmpty()) {
        c.addPushRefSpec(
            new RefSpec()
                .setSourceDestination("refs/*", "refs/*")
                .setForceUpdate(defaultForceUpdate));
      }

      Destination destination = destinationFactory.create(new DestinationConfiguration(c, config));

      if (!destination.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(
                String.format(
                    "remote.%s.url \"%s\" lacks ${name} placeholder in %s",
                    c.getName(), u, config.getFile()));
          }
        }
      }

      dest.add(destination);
    }
    return dest.build();
  }

  @Override
  public Multimap<Destination, URIish> getURIs(
      Optional<String> remoteName, Project.NameKey projectName, FilterType filterType) {
    if (getDestinations(filterType).isEmpty()) {
      return ImmutableMultimap.of();
    }

    SetMultimap<Destination, URIish> uris = HashMultimap.create();
    for (Destination config : getDestinations(filterType)) {
      if (!config.wouldPushProject(projectName)) {
        continue;
      }

      if (remoteName.isPresent() && !config.getRemoteConfigName().equals(remoteName.get())) {
        continue;
      }

      boolean adminURLUsed = false;

      for (String url : config.getAdminUrls()) {
        if (Strings.isNullOrEmpty(url)) {
          continue;
        }

        URIish uri;
        try {
          uri = new URIish(url);
        } catch (URISyntaxException e) {
          repLog.warn("adminURL '{}' is invalid: {}", url, e.getMessage());
          continue;
        }

        if (!isGerrit(uri) && !isGerritHttp(uri)) {
          String path =
              replaceName(uri.getPath(), projectName.get(), config.isSingleProjectMatch());
          if (path == null) {
            repLog.warn("adminURL {} does not contain ${name}", uri);
            continue;
          }

          uri = uri.setPath(path);
          if (!isSSH(uri)) {
            repLog.warn("adminURL '{}' is invalid: only SSH and HTTP are supported", uri);
            continue;
          }
        }
        uris.put(config, uri);
        adminURLUsed = true;
      }

      if (!adminURLUsed) {
        for (URIish uri : config.getURIs(projectName, "*")) {
          uris.put(config, uri);
        }
      }
    }
    return uris;
  }

  static String replaceName(String in, String name, boolean keyIsOptional) {
    String key = "${name}";
    int n = in.indexOf(key);
    if (0 <= n) {
      return in.substring(0, n) + name + in.substring(n + key.length());
    }
    if (keyIsOptional) {
      return in;
    }
    return null;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isReplicateAllOnPluginStart()
   */
  @Override
  public boolean isReplicateAllOnPluginStart() {
    return replicateAllOnPluginStart;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isDefaultForceUpdate()
   */
  @Override
  public boolean isDefaultForceUpdate() {
    return defaultForceUpdate;
  }

  @Override
  public int getMaxRefsToLog() {
    return maxRefsToLog;
  }

  private static List<RemoteConfig> allRemotes(FileBasedConfig cfg) throws ConfigInvalidException {
    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        result.add(new RemoteConfig(cfg, name));
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(
            String.format("remote %s has invalid URL in %s", name, cfg.getFile()));
      }
    }
    return result;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ReplicationConfig#isEmpty()
   */
  @Override
  public boolean isEmpty() {
    return destinations.isEmpty();
  }

  @Override
  public Path getEventsDirectory() {
    String eventsDirectory = config.getString("replication", null, "eventsDirectory");
    if (!Strings.isNullOrEmpty(eventsDirectory)) {
      return site.resolve(eventsDirectory);
    }
    return pluginDataDir;
  }

  Path getCfgPath() {
    return cfgPath;
  }

  @Override
  public int shutdown() {
    int discarded = 0;
    for (Destination cfg : destinations) {
      discarded += cfg.shutdown();
    }
    return discarded;
  }

  FileBasedConfig getConfig() {
    return config;
  }

  @Override
  public void startup(WorkQueue workQueue) {
    for (Destination cfg : destinations) {
      cfg.start(workQueue);
    }
  }
}
