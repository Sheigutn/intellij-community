// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing.hash;

import com.google.common.base.Charsets;
import com.intellij.execution.process.ProcessIOExecutorService;
import com.intellij.index.SharedIndexExtensions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.stubs.StubIndexExtension;
import com.intellij.psi.stubs.StubIndexImpl;
import com.intellij.psi.stubs.StubIndexKey;
import com.intellij.psi.stubs.StubUpdatingIndex;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.Processor;
import com.intellij.util.concurrency.SameThreadExecutor;
import com.intellij.util.concurrency.SequentialTaskExecutor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.hash.ContentHashEnumerator;
import com.intellij.util.indexing.*;
import com.intellij.util.indexing.hash.building.EmptyIndexEnumerator;
import com.intellij.util.indexing.provided.SharedIndexChunkLocator;
import com.intellij.util.indexing.provided.SharedIndexChunkLocator.ChunkDescriptor;
import com.intellij.util.indexing.provided.SharedIndexExtension;
import com.intellij.util.indexing.zipFs.UncompressedZipFileSystem;
import com.intellij.util.io.IOUtil;
import com.intellij.util.io.PersistentEnumeratorDelegate;
import com.intellij.util.io.PersistentStringEnumerator;
import com.intellij.util.io.zip.JBZipFile;
import gnu.trove.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@ApiStatus.Internal
public class SharedIndexChunkConfigurationImpl implements SharedIndexChunkConfiguration {
  private static final Logger LOG = Logger.getInstance(SharedIndexChunkConfigurationImpl.class);

  private final PersistentEnumeratorDelegate<String> myChunkDescriptorEnumerator;

  private final TIntObjectHashMap<ContentHashEnumerator> myChunkEnumerators = new TIntObjectHashMap<>();
  private final TIntLongHashMap myChunkTimestamps = new TIntLongHashMap();

  private final ConcurrentMap<ID<?, ?>, ConcurrentMap<Integer, SharedIndexChunk>> myChunkMap = new ConcurrentHashMap<>();

  private final UncompressedZipFileSystem myReadSystem;

  private final Map<Project, Long> myProjectStructureStamps = Collections.synchronizedMap(new WeakHashMap<>());

  private final Executor mySharedIndexExecutor = ApplicationManager.getApplication().isUnitTestMode()
    ? SameThreadExecutor.INSTANCE
    : SequentialTaskExecutor.createSequentialApplicationPoolExecutor("shared-index-loader", ProcessIOExecutorService.INSTANCE);

  @Override
  public <Value, Key> void processChunks(@NotNull ID<Key, Value> indexId, @NotNull Processor<UpdatableIndex<Key, Value, FileContent>> processor) {
    ConcurrentMap<Integer, SharedIndexChunk> map = myChunkMap.get(indexId);
    if (map == null) return;
    map.forEach((__, chunk) -> processor.process(chunk.getIndex()));
  }

  @Override
  @Nullable
  public <Value, Key> UpdatableIndex<Key, Value, FileContent> getChunk(@NotNull ID<Key, Value> indexId, int chunkId) {
    ConcurrentMap<Integer, SharedIndexChunk> map = myChunkMap.get(indexId);
    return map == null ? null : map.get(chunkId).getIndex();
  }

  public SharedIndexChunkConfigurationImpl() throws IOException {
    myChunkDescriptorEnumerator = new PersistentStringEnumerator(getSharedIndexConfigurationRoot().resolve("descriptors"));

    myReadSystem = UncompressedZipFileSystem.create(getSharedIndexStorage());
    Disposer.register(ApplicationManager.getApplication(), () -> {
      try {
        IOUtil.closeSafe(LOG, myChunkDescriptorEnumerator, myReadSystem);
      }
      catch (Throwable e) {
        ///TODO fix shared index deletion in tests
        LOG.info(e);
      }
    });

    ApplicationManager.getApplication().getMessageBus().connect().subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
      @Override
      public void projectClosed(@NotNull Project project) {
        List<SharedIndexChunk> toRemove =
          myChunkMap
            .values()
            .stream()
            .flatMap(chunks -> chunks.values().stream()).filter(chunk -> chunk.removeProject(project))
            .collect(Collectors.toList());

        TIntArrayList chunkIdsToRemove = new TIntArrayList();
        for (SharedIndexChunk chunk : toRemove) {
          chunkIdsToRemove.add(chunk.getChunkId());
          myChunkMap.get(chunk.getIndexName()).remove(chunk.getChunkId(), chunk);
          chunk.close();
        }

        Set<ContentHashEnumerator> enumerators = new THashSet<>();
        synchronized (myChunkEnumerators) {
          for (int chunkId : chunkIdsToRemove.toNativeArray()) {
            enumerators.add(myChunkEnumerators.remove(chunkId));
          }
        }
        for (ContentHashEnumerator enumerator : enumerators) {
          IOUtil.closeSafe(LOG, enumerator);
        }
      }
    });
  }

  @NotNull
  private static Path getSharedIndexStorage() throws IOException {
    Path storage = getSharedIndexConfigurationRoot().resolve("chunks.zip");
    if (!Files.exists(storage)) {
      JBZipFile newFile = new JBZipFile(storage.toFile());
      newFile.close();
    }
    return storage;
  }

  @Override
  public long tryEnumerateContentHash(byte[] hash) throws IOException {
    synchronized (myChunkEnumerators) {
      TIntObjectIterator<ContentHashEnumerator> iterator = myChunkEnumerators.iterator();
      while (iterator.hasNext()) {
        iterator.advance();

        ContentHashEnumerator enumerator = iterator.value();
        int id = Math.abs(enumerator.tryEnumerate(hash));
        if (id != 0) {
          return FileContentHashIndexExtension.getHashId(id, iterator.key());
        }
      }
    }
    return FileContentHashIndexExtension.NULL_HASH_ID;
  }

  private void attachChunk(@NotNull ChunkDescriptor chunkDescriptor,
                           @NotNull Project project) throws IOException {
    bindChunkToProject(registerChunk(chunkDescriptor), project);
  }

  private boolean bindChunkToProject(@NotNull Collection<SharedIndexChunk> chunkCollection,
                                     @NotNull Project project) {
    for (SharedIndexChunk chunk : chunkCollection) {
      ConcurrentMap<Integer, SharedIndexChunk> chunks = myChunkMap.computeIfAbsent(chunk.getIndexName(), __ -> new ConcurrentHashMap<>());
      chunk.addProject(project);
      chunks.put(chunk.getChunkId(), chunk);
    }
    return !chunkCollection.isEmpty();
  }

  @NotNull
  public Promise<Void> preloadIndex(@NotNull ChunkDescriptor descriptor,
                                    @NotNull ProgressIndicator indicator) {
    AsyncPromise<Void> p = new AsyncPromise<>();
    mySharedIndexExecutor.execute(() -> Promises.compute(p, () -> {
      loadSharedIndex(descriptor, indicator, IndexInfrastructureVersion.getIdeVersion());
      return null;
    }));
    return p;
  }

  // for now called sequentially on a single background thread
  private void loadSharedIndex(@NotNull ChunkDescriptor descriptor,
                               @NotNull ProgressIndicator indicator,
                               @NotNull IndexInfrastructureVersion ideVersion) {
    Path tempChunk = null;
    try {

      if (!chunkIsNotRegistered(descriptor)) {
        return;
      }

      long ms = System.currentTimeMillis();
      try {
        tempChunk = getSharedIndexConfigurationRoot().resolve(descriptor.getChunkUniqueId() + "_temp.zip");
        descriptor.downloadChunk(tempChunk, indicator);
        SharedIndexStorageUtil.appendToSharedIndexStorage(tempChunk, getSharedIndexStorage(), descriptor, ideVersion);
      } finally {
        LOG.info("Chunk " + descriptor.getChunkUniqueId() + " is downloaded in " + (System.currentTimeMillis() - ms) + " ms");
      }

    } catch (Exception e) {
      //noinspection InstanceofCatchParameter
      if (e instanceof ProcessCanceledException) ExceptionUtil.rethrow(e);
      LOG.error("Failed to download shared index " + descriptor + " " + e.getMessage(), e);
      return;
    } finally {
      deleteFile(tempChunk);
    }

    syncSharedIndexStorage();
  }

  private static void deleteFile(@Nullable Path sharedIndexChunk) {
    if (sharedIndexChunk != null) {
      try {
        FileUtil.delete(sharedIndexChunk);
      } catch (IOException ignored) {}
    }
  }

  private boolean chunkIsNotRegistered(@NotNull ChunkDescriptor descriptor) throws IOException {
    return myChunkDescriptorEnumerator.tryEnumerate(descriptor.getChunkUniqueId()) == 0;
  }

  private void syncSharedIndexStorage() {
    try {
      myReadSystem.sync();
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  @Override
  public void locateIndexes(@NotNull Project project,
                            @NotNull Set<OrderEntry> entries,
                            @NotNull ProgressIndicator indicator) {
    Runnable runnable = () -> {
      if (project.isDisposed()) return;
      Long evaluatedStamp = myProjectStructureStamps.get(project);
      long actualStamp = ProjectRootManager.getInstance(project).getModificationCount();
      if (Comparing.equal(evaluatedStamp, actualStamp)) return;

      IndexInfrastructureVersion ideVersion = IndexInfrastructureVersion.getIdeVersion();

      for (SharedIndexChunkLocator locator : SharedIndexChunkLocator.EP_NAME.getExtensionList()) {
        List<ChunkDescriptor> descriptors;
        try {
          descriptors = locator.locateIndex(project, entries, indicator);
        }
        catch (ProcessCanceledException e) {
          throw e;
        }
        catch (Throwable t) {
          LOG.error("Failed to execute SharedIndexChunkLocator: " + locator.getClass() + ". " + t.getMessage(), t);
          continue;
        }

        for (ChunkDescriptor descriptor : descriptors) {
          if (ideVersion.getBaseIndexes().equals(descriptor.getSupportedInfrastructureVersion().getBaseIndexes())) {
            LOG.info("Found shared index " + descriptor.getChunkUniqueId() + " for " + descriptor.getOrderEntries());
            if (!project.isDisposed()) {
              loadSharedIndex(descriptor, indicator, ideVersion);
            }
          } else {
            LOG.info("Shared index " + descriptor.getChunkUniqueId() + " is unsuported");
          }

          try {
            attachChunk(descriptor, project);
          }
          catch (IOException e) {
            LOG.error(e);
          }
        }
      }

      myProjectStructureStamps.put(project, actualStamp);
    };

    mySharedIndexExecutor.execute(runnable);
  }

  @Override
  public boolean attachExistingChunk(int chunkId, @NotNull Project project) {
    if (chunkId == FileContentHashIndexExtension.NULL_INDEX_ID) return true;
    boolean isChunkLoaded;
    synchronized (myChunkEnumerators) {
      isChunkLoaded = myChunkEnumerators.containsKey(chunkId);
    }
    List<@NotNull SharedIndexChunk> chunkIndexes;
    if (isChunkLoaded) {
      chunkIndexes = ContainerUtil.mapNotNull(myChunkMap.values(), m -> m.get(chunkId));
    } else {
      try {
        chunkIndexes = registerChunk(chunkId);
      }
      catch (IOException e) {
        LOG.error(e);
        return false;
      }
    }
    return bindChunkToProject(chunkIndexes, project);
  }

  @NotNull
  private List<SharedIndexChunk> registerChunk(@NotNull ChunkDescriptor descriptor) throws IOException {
    String chunkRootName = descriptor.getChunkUniqueId();
    int chunkId = myChunkDescriptorEnumerator.enumerate(chunkRootName);
    if (chunkId == 0) {
      throw new RuntimeException("chunk " + chunkRootName + " is not present");
    }
    return registerChunk(chunkId);
  }

  @NotNull
  private List<SharedIndexChunk> registerChunk(int chunkId) throws IOException {
    ContentHashEnumerator enumerator;
    String chunkRootName = myChunkDescriptorEnumerator.valueOf(chunkId);
    LOG.assertTrue(chunkRootName != null);
    Path chunkRoot = myReadSystem.getPath(chunkRootName);
    synchronized (myChunkEnumerators) {
      enumerator = myChunkEnumerators.get(chunkId);
      if (enumerator == null) {
        //TODO: let's add "readOnly = true" parameter to ContentHashEnumerator to make it clear that we are not going to write to it.
        myChunkEnumerators.put(chunkId, new ContentHashEnumerator(chunkRoot.resolve("hashes")));
      }
    }
    long timestamp;
    synchronized (myChunkTimestamps) {
      timestamp = myChunkTimestamps.get(chunkId);
      if (timestamp == 0) {
        timestamp = getTimestamp(chunkRoot);
        if (timestamp == -1) {
          throw new RuntimeException("corrupted shared index");
        }
        myChunkTimestamps.put(chunkId, timestamp);
      }
    }

    Set<String> emptyIndexNames = EmptyIndexEnumerator.readEmptyIndexes(chunkRoot);
    Set<String> emptyStubIndexNames = EmptyIndexEnumerator.readEmptyStubIndexes(chunkRoot);

    List<SharedIndexChunk> result = new ArrayList<>();
    for (Path indexDir : Files.newDirectoryStream(chunkRoot)) {
      ID<?, ?> id = ID.findByName(indexDir.getFileName().toString());
      if (id != null) {
        FileBasedIndexExtension<?, ?> extension = FileBasedIndexExtension.EXTENSION_POINT_NAME.findFirstSafe(ex -> ex.getName().equals(id));
        if (extension != null) {
          SharedIndexExtension sharedExtension = SharedIndexExtensions.findExtension(extension);
          SharedIndexChunk chunk = new SharedIndexChunk(chunkRoot,
                                                        id,
                                                        chunkId,
                                                        timestamp,
                                                        emptyIndexNames.contains(id.getName()) || emptyStubIndexNames.contains(id.getName()),
                                                        sharedExtension,
                                                        extension,
                                                        ((FileBasedIndexImpl)FileBasedIndex.getInstance()).getOrCreateFileContentHashIndex());
          result.add(chunk);
        }
        if (id.equals(StubUpdatingIndex.INDEX_ID)) {
          result.addAll(registerStubIndexChunks(indexDir, emptyStubIndexNames, chunkId, timestamp));
        }
      }
    }

    return result;
  }

  private static List<SharedIndexChunk> registerStubIndexChunks(@NotNull Path stubIndexDir,
                                                                @NotNull Set<String> emptyStubIndexNames,
                                                                int chunkId,
                                                                long timestamp) throws IOException {
    List<SharedIndexChunk> result = new ArrayList<>();
    for (Path stubIndexExDir : Files.newDirectoryStream(stubIndexDir)) {
      ID<?, ?> id = ID.findByName(stubIndexExDir.getFileName().toString());
      if (id instanceof StubIndexKey) {
        FileBasedIndexExtension<?, ?> extension = StubIndexImpl.wrapStubIndexExtension(StubIndexExtension.EP_NAME.findFirstSafe(ex -> ex.getKey().equals(id)));
        SharedIndexExtension sharedExtension = SharedIndexExtensions.findExtension(extension);
        SharedIndexChunk chunk = new SharedIndexChunk(stubIndexDir,
                                                      id,
                                                      chunkId,
                                                      timestamp,
                                                      emptyStubIndexNames.contains(id.getName()),
                                                      sharedExtension,
                                                      extension,
                                                      ((FileBasedIndexImpl)FileBasedIndex.getInstance()).getOrCreateFileContentHashIndex());
        result.add(chunk);
      }
    }
    return result;
  }

  public static long getTimestamp(@NotNull Path chunkPath) {
    try {
      return StringUtil.parseLong(new String(Files.readAllBytes(chunkPath.resolve("timestamp")), Charsets.UTF_8), 0);
    }
    catch (IOException e) {
      LOG.info("timestamp is not found in " + chunkPath);
      return 0;
    }
  }

  public static void setTimestamp(@NotNull Path chunkPath, long timestamp) {
    byte[] bytes = String.valueOf(timestamp).getBytes(Charsets.UTF_8);
    try {
      Files.write(chunkPath.resolve("timestamp"), bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  @NotNull
  private static Path getSharedIndexConfigurationRoot() throws IOException {
    Path root = PathManager.getIndexRoot().toPath().resolve("shared_indexes");
    if (!Files.exists(root)) {
      Files.createDirectories(root);
    }
    return root;
  }
}
