/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.upstream.cache;

import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.google.android.exoplayer2.upstream.cache.Cache.CacheException;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.AtomicFile;
import com.google.android.exoplayer2.util.ReusableBufferedOutputStream;
import com.google.android.exoplayer2.util.Util;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.checkerframework.checker.nullness.compatqual.NullableType;

/** Maintains the index of cached content. */
/* package */ class CachedContentIndex {

  public static final String FILE_NAME = "cached_content_index.exi";

  private static final int VERSION = 2;
  private static final int VERSION_METADATA_INTRODUCED = 2;
  private static final int INCREMENTAL_METADATA_READ_LENGTH = 10 * 1024 * 1024;

  private static final int FLAG_ENCRYPTED_INDEX = 1;

  private final HashMap<String, CachedContent> keyToContent;
  /**
   * Maps assigned ids to their corresponding keys. Also contains (id -> null) entries for ids that
   * have been removed from the index since it was last stored. This prevents reuse of these ids,
   * which is necessary to avoid clashes that could otherwise occur as a result of the sequence:
   *
   * <p>[1] (key1, id1) is removed from the in-memory index ... the index is not stored to disk ...
   * [2] id1 is reused for a different key2 ... the index is not stored to disk ... [3] A file for
   * key2 is partially written using a path corresponding to id1 ... the process is killed before
   * the index is stored to disk ... [4] The index is read from disk, causing the partially written
   * file to be incorrectly associated to key1
   *
   * <p>By avoiding id reuse in step [2], a new id2 will be used instead. Step [4] will then delete
   * the partially written file because the index does not contain an entry for id2.
   *
   * <p>When the index is next stored (id -> null) entries are removed, making the ids eligible for
   * reuse.
   */
  private final SparseArray<@NullableType String> idToKey;
  /**
   * Tracks ids for which (id -> null) entries are present in idToKey, so that they can be removed
   * efficiently when the index is next stored.
   */
  private final SparseBooleanArray removedIds;

  private final Storage storage;

  /**
   * Creates a CachedContentIndex which works on the index file in the given cacheDir.
   *
   * @param cacheDir Directory where the index file is kept.
   */
  public CachedContentIndex(File cacheDir) {
    this(cacheDir, null);
  }

  /**
   * Creates a CachedContentIndex which works on the index file in the given cacheDir.
   *
   * @param cacheDir Directory where the index file is kept.
   * @param secretKey 16 byte AES key for reading and writing the cache index.
   */
  public CachedContentIndex(File cacheDir, byte[] secretKey) {
    this(cacheDir, secretKey, secretKey != null);
  }

  /**
   * Creates a CachedContentIndex which works on the index file in the given cacheDir.
   *
   * @param cacheDir Directory where the index file is kept.
   * @param secretKey 16 byte AES key for reading, and optionally writing, the cache index.
   * @param encrypt Whether the index will be encrypted when written. Must be false if {@code
   *     secretKey} is null.
   */
  public CachedContentIndex(File cacheDir, byte[] secretKey, boolean encrypt) {
    Cipher cipher = null;
    SecretKeySpec secretKeySpec = null;
    if (secretKey != null) {
      Assertions.checkArgument(secretKey.length == 16);
      try {
        cipher = getCipher();
        secretKeySpec = new SecretKeySpec(secretKey, "AES");
      } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
        throw new IllegalStateException(e); // Should never happen.
      }
    } else {
      Assertions.checkState(!encrypt);
    }
    keyToContent = new HashMap<>();
    idToKey = new SparseArray<>();
    removedIds = new SparseBooleanArray();
    storage = new AtomicFileStorage(new File(cacheDir, FILE_NAME), encrypt, cipher, secretKeySpec);
  }

  /** Loads the index file. */
  public void load() {
    if (!storage.load(keyToContent, idToKey)) {
      keyToContent.clear();
      idToKey.clear();
    }
  }

  /** Stores the index data to index file if there is a change. */
  public void store() throws CacheException {
    storage.store(keyToContent);
    // Make ids that were removed since the index was last stored eligible for re-use.
    int removedIdCount = removedIds.size();
    for (int i = 0; i < removedIdCount; i++) {
      idToKey.remove(removedIds.keyAt(i));
    }
    removedIds.clear();
  }

  /**
   * Adds the given key to the index if it isn't there already.
   *
   * @param key The cache key that uniquely identifies the original stream.
   * @return A new or existing CachedContent instance with the given key.
   */
  public CachedContent getOrAdd(String key) {
    CachedContent cachedContent = keyToContent.get(key);
    return cachedContent == null ? addNew(key) : cachedContent;
  }

  /** Returns a CachedContent instance with the given key or null if there isn't one. */
  public CachedContent get(String key) {
    return keyToContent.get(key);
  }

  /**
   * Returns a Collection of all CachedContent instances in the index. The collection is backed by
   * the {@code keyToContent} map, so changes to the map are reflected in the collection, and
   * vice-versa. If the map is modified while an iteration over the collection is in progress
   * (except through the iterator's own remove operation), the results of the iteration are
   * undefined.
   */
  public Collection<CachedContent> getAll() {
    return keyToContent.values();
  }

  /** Returns an existing or new id assigned to the given key. */
  public int assignIdForKey(String key) {
    return getOrAdd(key).id;
  }

  /** Returns the key which has the given id assigned. */
  public String getKeyForId(int id) {
    return idToKey.get(id);
  }

  /** Removes {@link CachedContent} with the given key from index if it's empty and not locked. */
  public void maybeRemove(String key) {
    CachedContent cachedContent = keyToContent.get(key);
    if (cachedContent != null && cachedContent.isEmpty() && !cachedContent.isLocked()) {
      keyToContent.remove(key);
      storage.onRemove(cachedContent);
      // Keep an entry in idToKey to stop the id from being reused until the index is next stored.
      idToKey.put(cachedContent.id, /* value= */ null);
      // Track that the entry should be removed from idToKey when the index is next stored.
      removedIds.put(cachedContent.id, /* value= */ true);
    }
  }

  /** Removes empty and not locked {@link CachedContent} instances from index. */
  public void removeEmpty() {
    String[] keys = new String[keyToContent.size()];
    keyToContent.keySet().toArray(keys);
    for (String key : keys) {
      maybeRemove(key);
    }
  }

  /**
   * Returns a set of all content keys. The set is backed by the {@code keyToContent} map, so
   * changes to the map are reflected in the set, and vice-versa. If the map is modified while an
   * iteration over the set is in progress (except through the iterator's own remove operation), the
   * results of the iteration are undefined.
   */
  public Set<String> getKeys() {
    return keyToContent.keySet();
  }

  /**
   * Applies {@code mutations} to the {@link ContentMetadata} for the given key. A new {@link
   * CachedContent} is added if there isn't one already with the given key.
   */
  public void applyContentMetadataMutations(String key, ContentMetadataMutations mutations) {
    CachedContent cachedContent = getOrAdd(key);
    if (cachedContent.applyMetadataMutations(mutations)) {
      storage.onUpdate(cachedContent);
    }
  }

  /** Returns a {@link ContentMetadata} for the given key. */
  public ContentMetadata getContentMetadata(String key) {
    CachedContent cachedContent = get(key);
    return cachedContent != null ? cachedContent.getMetadata() : DefaultContentMetadata.EMPTY;
  }

  private CachedContent addNew(String key) {
    int id = getNewId(idToKey);
    CachedContent cachedContent = new CachedContent(id, key);
    keyToContent.put(cachedContent.key, cachedContent);
    idToKey.put(cachedContent.id, cachedContent.key);
    storage.onUpdate(cachedContent);
    return cachedContent;
  }

  private static Cipher getCipher() throws NoSuchPaddingException, NoSuchAlgorithmException {
    // Workaround for https://issuetracker.google.com/issues/36976726
    if (Util.SDK_INT == 18) {
      try {
        return Cipher.getInstance("AES/CBC/PKCS5PADDING", "BC");
      } catch (Throwable ignored) {
        // ignored
      }
    }
    return Cipher.getInstance("AES/CBC/PKCS5PADDING");
  }

  /**
   * Returns an id which isn't used in the given array. If the maximum id in the array is smaller
   * than {@link java.lang.Integer#MAX_VALUE} it just returns the next bigger integer. Otherwise it
   * returns the smallest unused non-negative integer.
   */
  @VisibleForTesting
  /* package */ static int getNewId(SparseArray<String> idToKey) {
    int size = idToKey.size();
    int id = size == 0 ? 0 : (idToKey.keyAt(size - 1) + 1);
    if (id < 0) { // In case if we pass max int value.
      // TODO optimization: defragmentation or binary search?
      for (id = 0; id < size; id++) {
        if (id != idToKey.keyAt(id)) {
          break;
        }
      }
    }
    return id;
  }

  /**
   * Deserializes a {@link DefaultContentMetadata} from the given input stream.
   *
   * @param input Input stream to read from.
   * @return a {@link DefaultContentMetadata} instance.
   * @throws IOException If an error occurs during reading from input.
   */
  private static DefaultContentMetadata readContentMetadata(DataInputStream input)
      throws IOException {
    int size = input.readInt();
    HashMap<String, byte[]> metadata = new HashMap<>();
    for (int i = 0; i < size; i++) {
      String name = input.readUTF();
      int valueSize = input.readInt();
      if (valueSize < 0) {
        throw new IOException("Invalid value size: " + valueSize);
      }
      // Grow the array incrementally to avoid OutOfMemoryError in the case that a corrupt (and very
      // large) valueSize was read. In such cases the implementation below is expected to throw
      // IOException from one of the readFully calls, due to the end of the input being reached.
      int bytesRead = 0;
      int nextBytesToRead = Math.min(valueSize, INCREMENTAL_METADATA_READ_LENGTH);
      byte[] value = Util.EMPTY_BYTE_ARRAY;
      while (bytesRead != valueSize) {
        value = Arrays.copyOf(value, bytesRead + nextBytesToRead);
        input.readFully(value, bytesRead, nextBytesToRead);
        bytesRead += nextBytesToRead;
        nextBytesToRead = Math.min(valueSize - bytesRead, INCREMENTAL_METADATA_READ_LENGTH);
      }
      metadata.put(name, value);
    }
    return new DefaultContentMetadata(metadata);
  }

  /**
   * Serializes itself to a {@link DataOutputStream}.
   *
   * @param output Output stream to store the values.
   * @throws IOException If an error occurs during writing values to output.
   */
  private static void writeContentMetadata(DefaultContentMetadata metadata, DataOutputStream output)
      throws IOException {
    Set<Map.Entry<String, byte[]>> entrySet = metadata.entrySet();
    output.writeInt(entrySet.size());
    for (Map.Entry<String, byte[]> entry : entrySet) {
      output.writeUTF(entry.getKey());
      byte[] value = entry.getValue();
      output.writeInt(value.length);
      output.write(value);
    }
  }

  /** Interface for the persistent index. */
  private interface Storage {

    /**
     * Loads the persisted index into {@code content} and {@code idToKey}.
     *
     * @param content The key to content map to populate with persisted data.
     * @param idToKey The id to key map to populate with persisted data.
     * @return Whether the load was successful.
     */
    boolean load(HashMap<String, CachedContent> content, SparseArray<@NullableType String> idToKey);

    /**
     * Ensures all changes in the in-memory table are persisted.
     *
     * @param content The key to content map to persist.
     * @throws CacheException If an error occurs persisting the index.
     */
    void store(HashMap<String, CachedContent> content) throws CacheException;

    /**
     * Called when a {@link CachedContent} is added or updated in the in-memory index.
     *
     * @param cachedContent The updated {@link CachedContent}.
     */
    void onUpdate(CachedContent cachedContent);

    /**
     * Called when a {@link CachedContent} is removed from the in-memory index.
     *
     * @param cachedContent The removed {@link CachedContent}.
     */
    void onRemove(CachedContent cachedContent);
  }

  /** {@link Storage} implementation that uses an {@link AtomicFile}. */
  private static class AtomicFileStorage implements Storage {

    private final boolean encrypt;
    @Nullable private final Cipher cipher;
    @Nullable private final SecretKeySpec secretKeySpec;
    private final AtomicFile atomicFile;
    private final Random random;

    private boolean changed;
    @Nullable private ReusableBufferedOutputStream bufferedOutputStream;

    public AtomicFileStorage(
        File fileName,
        boolean encrypt,
        @Nullable Cipher cipher,
        @Nullable SecretKeySpec secretKeySpec) {
      this.encrypt = encrypt;
      this.cipher = cipher;
      this.secretKeySpec = secretKeySpec;
      atomicFile = new AtomicFile(fileName);
      random = new Random();
    }

    @Override
    public boolean load(
        HashMap<String, CachedContent> content, SparseArray<@NullableType String> idToKey) {
      Assertions.checkState(!changed);
      if (!readFile(content, idToKey)) {
        atomicFile.delete();
        return false;
      }
      return true;
    }

    @Override
    public void store(HashMap<String, CachedContent> content) throws CacheException {
      if (!changed) {
        return;
      }
      writeFile(content);
      changed = false;
    }

    @Override
    public void onUpdate(CachedContent cachedContent) {
      changed = true;
    }

    @Override
    public void onRemove(CachedContent cachedContent) {
      changed = true;
    }

    private boolean readFile(
        HashMap<String, CachedContent> content, SparseArray<@NullableType String> idToKey) {
      DataInputStream input = null;
      try {
        InputStream inputStream = new BufferedInputStream(atomicFile.openRead());
        input = new DataInputStream(inputStream);
        int version = input.readInt();
        if (version < 0 || version > VERSION) {
          return false;
        }

        int flags = input.readInt();
        if ((flags & FLAG_ENCRYPTED_INDEX) != 0) {
          if (cipher == null) {
            return false;
          }
          byte[] initializationVector = new byte[16];
          input.readFully(initializationVector);
          IvParameterSpec ivParameterSpec = new IvParameterSpec(initializationVector);
          try {
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
          } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IllegalStateException(e);
          }
          input = new DataInputStream(new CipherInputStream(inputStream, cipher));
        } else if (encrypt) {
          changed = true; // Force index to be rewritten encrypted after read.
        }

        int count = input.readInt();
        int hashCode = 0;
        for (int i = 0; i < count; i++) {
          CachedContent cachedContent = readCachedContent(version, input);
          content.put(cachedContent.key, cachedContent);
          idToKey.put(cachedContent.id, cachedContent.key);
          hashCode += hashCachedContent(cachedContent, version);
        }
        int fileHashCode = input.readInt();
        boolean isEOF = input.read() == -1;
        if (fileHashCode != hashCode || !isEOF) {
          return false;
        }
      } catch (IOException e) {
        return false;
      } finally {
        if (input != null) {
          Util.closeQuietly(input);
        }
      }
      return true;
    }

    private void writeFile(HashMap<String, CachedContent> content) throws CacheException {
      DataOutputStream output = null;
      try {
        OutputStream outputStream = atomicFile.startWrite();
        if (bufferedOutputStream == null) {
          bufferedOutputStream = new ReusableBufferedOutputStream(outputStream);
        } else {
          bufferedOutputStream.reset(outputStream);
        }
        output = new DataOutputStream(bufferedOutputStream);
        output.writeInt(VERSION);

        int flags = encrypt ? FLAG_ENCRYPTED_INDEX : 0;
        output.writeInt(flags);

        if (encrypt) {
          byte[] initializationVector = new byte[16];
          random.nextBytes(initializationVector);
          output.write(initializationVector);
          IvParameterSpec ivParameterSpec = new IvParameterSpec(initializationVector);
          try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
          } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw new IllegalStateException(e); // Should never happen.
          }
          output.flush();
          output = new DataOutputStream(new CipherOutputStream(bufferedOutputStream, cipher));
        }

        output.writeInt(content.size());
        int hashCode = 0;
        for (CachedContent cachedContent : content.values()) {
          writeCachedContent(cachedContent, output);
          hashCode += hashCachedContent(cachedContent, VERSION);
        }
        output.writeInt(hashCode);
        atomicFile.endWrite(output);
        // Avoid calling close twice. Duplicate CipherOutputStream.close calls did
        // not used to be no-ops: https://android-review.googlesource.com/#/c/272799/
        output = null;
      } catch (IOException e) {
        throw new CacheException(e);
      } finally {
        Util.closeQuietly(output);
      }
    }

    /**
     * Calculates a hash code for a {@link CachedContent} which is compatible with a particular
     * index version.
     */
    private int hashCachedContent(CachedContent cachedContent, int version) {
      int result = cachedContent.id;
      result = 31 * result + cachedContent.key.hashCode();
      if (version < VERSION_METADATA_INTRODUCED) {
        long length = ContentMetadata.getContentLength(cachedContent.getMetadata());
        result = 31 * result + (int) (length ^ (length >>> 32));
      } else {
        result = 31 * result + cachedContent.getMetadata().hashCode();
      }
      return result;
    }

    /**
     * Reads a {@link CachedContent} from a {@link DataInputStream}.
     *
     * @param version Version of the encoded data.
     * @param input Input stream containing values needed to initialize CachedContent instance.
     * @throws IOException If an error occurs during reading values.
     */
    private CachedContent readCachedContent(int version, DataInputStream input) throws IOException {
      int id = input.readInt();
      String key = input.readUTF();
      DefaultContentMetadata metadata;
      if (version < VERSION_METADATA_INTRODUCED) {
        long length = input.readLong();
        ContentMetadataMutations mutations = new ContentMetadataMutations();
        ContentMetadataMutations.setContentLength(mutations, length);
        metadata = DefaultContentMetadata.EMPTY.copyWithMutationsApplied(mutations);
      } else {
        metadata = readContentMetadata(input);
      }
      return new CachedContent(id, key, metadata);
    }

    /**
     * Writes a {@link CachedContent} to a {@link DataOutputStream}.
     *
     * @param output Output stream to store the values.
     * @throws IOException If an error occurs during writing values to output.
     */
    private void writeCachedContent(CachedContent cachedContent, DataOutputStream output)
        throws IOException {
      output.writeInt(cachedContent.id);
      output.writeUTF(cachedContent.key);
      writeContentMetadata(cachedContent.getMetadata(), output);
    }
  }
}