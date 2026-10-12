package io.earthplatform.minecraft.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Test-only provider over caller-owned temporary files. A selected directory entry
 * remains enumerable but its attributes disappear, without racing or deleting it.
 * No provider is installed globally and closing this fixture never closes the
 * backing filesystem. Construct it only with a test temporary root.
 */
final class VanishingJournalFileSystem extends FileSystem {
    enum Operation {
        ROOT_ATTRIBUTES, CHILD_ATTRIBUTES, DIRECTORY_OPEN, DIRECTORY_ITERATE,
        DIRECTORY_CLOSE, CHANNEL_OPEN, DELETE, WRITE, MOVE, FORCE
    }

    private final Path backingRoot;
    private final FileSystem backing;
    private final Provider provider = new Provider();
    private final Map<Operation, Integer> counts = new EnumMap<>(Operation.class);
    private final Map<Operation, IOException> faults = new EnumMap<>(Operation.class);
    private final Map<Operation, Integer> successfulCallsBeforeFailure = new EnumMap<>(Operation.class);
    private final Map<String, Integer> childAttributeReads = new LinkedHashMap<>();
    private final Set<String> vanishingChildren = new HashSet<>();
    private int iterationFailureAfter = -1;
    private IOException iterationFailure;
    private volatile boolean open = true;

    VanishingJournalFileSystem(Path root) {
        backingRoot = Objects.requireNonNull(root).toAbsolutePath().normalize();
        backing = root.getFileSystem();
    }

    Path root() { return wrap(backingRoot); }

    Path wrap(Path path) {
        if (path instanceof WrappedPath wrapped) {
            if (wrapped.getFileSystem() != this) throw new ProviderMismatchException();
            return wrapped;
        }
        if (path.getFileSystem() != backing) throw new ProviderMismatchException();
        return new WrappedPath(path);
    }

    synchronized VanishingJournalFileSystem failChildAttributes(String name) {
        vanishingChildren.add(Objects.requireNonNull(name));
        return this;
    }

    synchronized VanishingJournalFileSystem fail(Operation operation, IOException failure) {
        return failAfter(operation, 0, failure);
    }

    /** Count from installation of the fault, independently of resetCounts(). */
    synchronized VanishingJournalFileSystem failAfter(Operation operation, int successfulCalls, IOException failure) {
        if (successfulCalls < 0) throw new IllegalArgumentException("Negative successful call count");
        faults.put(Objects.requireNonNull(operation), Objects.requireNonNull(failure));
        successfulCallsBeforeFailure.put(operation, successfulCalls);
        return this;
    }

    /** Fail iteration after the specified number of successfully emitted entries. */
    synchronized VanishingJournalFileSystem failDirectoryIterationAfter(int entries, IOException failure) {
        if (entries < 0) throw new IllegalArgumentException("Negative entry count");
        iterationFailureAfter = entries;
        iterationFailure = Objects.requireNonNull(failure);
        return this;
    }

    synchronized int count(Operation operation) { return counts.getOrDefault(operation, 0); }
    synchronized Map<Operation, Integer> counts() { return Map.copyOf(counts); }
    synchronized int childAttributeReads(String name) { return childAttributeReads.getOrDefault(name, 0); }

    synchronized void resetCounts() {
        counts.clear();
        childAttributeReads.clear();
    }

    synchronized void resetFaults() {
        faults.clear();
        successfulCallsBeforeFailure.clear();
        vanishingChildren.clear();
        iterationFailureAfter = -1;
        iterationFailure = null;
    }

    private synchronized void before(Operation operation) throws IOException {
        counts.merge(operation, 1, Integer::sum);
        IOException failure = faults.get(operation);
        if (failure != null) {
            int remaining = successfulCallsBeforeFailure.get(operation);
            if (remaining == 0) throw failure;
            successfulCallsBeforeFailure.put(operation, remaining - 1);
        }
    }

    private synchronized void beforeAttributes(Path path) throws IOException {
        if (path.toAbsolutePath().normalize().equals(backingRoot)) {
            before(Operation.ROOT_ATTRIBUTES);
        } else {
            String name = path.getFileName().toString();
            childAttributeReads.merge(name, 1, Integer::sum);
            before(Operation.CHILD_ATTRIBUTES);
            if (vanishingChildren.contains(name)) throw new NoSuchFileException(path.toString());
        }
    }

    private synchronized void checkIteration(int emitted) throws IOException {
        if (iterationFailureAfter >= 0 && emitted >= iterationFailureAfter) throw iterationFailure;
    }

    private Path unwrap(Path path) {
        if (!open) throw new ClosedFileSystemException();
        return unwrapPath(path);
    }

    private Path unwrapPath(Path path) {
        if (!(path instanceof WrappedPath wrapped) || wrapped.getFileSystem() != this)
            throw new ProviderMismatchException();
        return wrapped.delegate;
    }

    @Override public FileSystemProvider provider() { return provider; }
    @Override public void close() { open = false; }
    @Override public boolean isOpen() { return open; }
    @Override public boolean isReadOnly() { return backing.isReadOnly(); }
    @Override public String getSeparator() { return backing.getSeparator(); }
    @Override public Iterable<Path> getRootDirectories() {
        List<Path> roots = new ArrayList<>();
        backing.getRootDirectories().forEach(path -> roots.add(wrap(path)));
        return List.copyOf(roots);
    }
    @Override public Iterable<FileStore> getFileStores() { return backing.getFileStores(); }
    @Override public Set<String> supportedFileAttributeViews() { return backing.supportedFileAttributeViews(); }
    @Override public Path getPath(String first, String... more) { return wrap(backing.getPath(first, more)); }
    @Override public PathMatcher getPathMatcher(String syntaxAndPattern) {
        PathMatcher matcher = backing.getPathMatcher(syntaxAndPattern);
        return path -> matcher.matches(unwrapPath(path));
    }
    @Override public java.nio.file.attribute.UserPrincipalLookupService getUserPrincipalLookupService() {
        return backing.getUserPrincipalLookupService();
    }
    @Override public WatchService newWatchService() throws IOException { return backing.newWatchService(); }

    private final class Provider extends FileSystemProvider {
        private FileSystemProvider delegate() { return backing.provider(); }

        @Override public String getScheme() { return "vanishing-journal"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException("Construct a fixture with a temporary root");
        }
        @Override public FileSystem getFileSystem(URI uri) {
            if (!getScheme().equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Wrong scheme");
            return VanishingJournalFileSystem.this;
        }
        @Override public Path getPath(URI uri) {
            getFileSystem(uri);
            return wrap(backing.getPath(uri.getPath()));
        }

        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                            FileAttribute<?>... attrs) throws IOException {
            Path real = unwrap(path);
            before(Operation.CHANNEL_OPEN);
            SeekableByteChannel channel = delegate().newByteChannel(real, options, attrs);
            return new SeekableByteChannel() {
                @Override public int read(ByteBuffer dst) throws IOException { return channel.read(dst); }
                @Override public int write(ByteBuffer src) throws IOException {
                    before(Operation.WRITE);
                    return channel.write(src);
                }
                @Override public long position() throws IOException { return channel.position(); }
                @Override public SeekableByteChannel position(long position) throws IOException {
                    channel.position(position); return this;
                }
                @Override public long size() throws IOException { return channel.size(); }
                @Override public SeekableByteChannel truncate(long size) throws IOException {
                    channel.truncate(size); return this;
                }
                @Override public boolean isOpen() { return channel.isOpen(); }
                @Override public void close() throws IOException { channel.close(); }
            };
        }

        @Override public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options,
                                                    FileAttribute<?>... attrs) throws IOException {
            Path real = unwrap(path);
            before(Operation.CHANNEL_OPEN);
            return new WrappedFileChannel(delegate().newFileChannel(real, options, attrs));
        }

        @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
                throws IOException {
            Path real = unwrap(dir);
            before(Operation.DIRECTORY_OPEN);
            DirectoryStream<Path> stream = delegate().newDirectoryStream(real, child -> filter.accept(wrap(child)));
            return new DirectoryStream<>() {
                private boolean closed;

                @Override public Iterator<Path> iterator() {
                    Iterator<Path> iterator = stream.iterator();
                    return new Iterator<>() {
                        private int emitted;
                        private boolean prepared;
                        private boolean available;

                        @Override public boolean hasNext() {
                            if (!prepared) {
                                try {
                                    before(Operation.DIRECTORY_ITERATE);
                                    checkIteration(emitted);
                                } catch (IOException failure) {
                                    throw new DirectoryIteratorException(failure);
                                }
                                available = iterator.hasNext();
                                prepared = true;
                            }
                            return available;
                        }

                        @Override public Path next() {
                            if (!hasNext()) throw new java.util.NoSuchElementException();
                            Path next = wrap(iterator.next());
                            emitted++;
                            prepared = false;
                            return next;
                        }
                    };
                }

                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    IOException failure = null;
                    try { before(Operation.DIRECTORY_CLOSE); }
                    catch (IOException injected) { failure = injected; }
                    try { stream.close(); }
                    catch (IOException actual) {
                        if (failure == null) failure = actual;
                        else if (failure != actual) failure.addSuppressed(actual);
                    }
                    if (failure != null) throw failure;
                }
            };
        }

        @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            delegate().createDirectory(unwrap(dir), attrs);
        }
        @Override public void delete(Path path) throws IOException {
            Path real = unwrap(path);
            before(Operation.DELETE);
            delegate().delete(real);
        }
        @Override public void copy(Path source, Path target, CopyOption... options) throws IOException {
            delegate().copy(unwrap(source), unwrap(target), options);
        }
        @Override public void move(Path source, Path target, CopyOption... options) throws IOException {
            Path realSource = unwrap(source);
            Path realTarget = unwrap(target);
            before(Operation.MOVE);
            delegate().move(realSource, realTarget, options);
        }
        @Override public boolean isSameFile(Path path, Path other) throws IOException {
            return delegate().isSameFile(unwrap(path), unwrap(other));
        }
        @Override public boolean isHidden(Path path) throws IOException { return delegate().isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return delegate().getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException {
            delegate().checkAccess(unwrap(path), modes);
        }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
                                                                            LinkOption... options) {
            Path real = unwrap(path);
            V view = delegate().getFileAttributeView(real, type, options);
            if (view == null || type != BasicFileAttributeView.class) return view;
            BasicFileAttributeView basic = (BasicFileAttributeView) view;
            return type.cast(new BasicFileAttributeView() {
                @Override public String name() { return basic.name(); }
                @Override public BasicFileAttributes readAttributes() throws IOException {
                    beforeAttributes(real);
                    return basic.readAttributes();
                }
                @Override public void setTimes(FileTime modified, FileTime accessed, FileTime created) throws IOException {
                    basic.setTimes(modified, accessed, created);
                }
            });
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
                                                                         LinkOption... options) throws IOException {
            Path real = unwrap(path);
            beforeAttributes(real);
            return delegate().readAttributes(real, type, options);
        }
        @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
                throws IOException {
            Path real = unwrap(path);
            beforeAttributes(real);
            return delegate().readAttributes(real, attributes, options);
        }
        @Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
                throws IOException {
            delegate().setAttribute(unwrap(path), attribute, value, options);
        }
        @Override public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
            delegate().createSymbolicLink(unwrap(link), unwrap(target), attrs);
        }
        @Override public void createLink(Path link, Path existing) throws IOException {
            delegate().createLink(unwrap(link), unwrap(existing));
        }
        @Override public Path readSymbolicLink(Path link) throws IOException {
            return wrap(delegate().readSymbolicLink(unwrap(link)));
        }
    }

    private final class WrappedFileChannel extends FileChannel {
        private final FileChannel channel;
        private WrappedFileChannel(FileChannel channel) { this.channel = channel; }
        @Override public int read(ByteBuffer dst) throws IOException { return channel.read(dst); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return channel.read(dsts, offset, length);
        }
        @Override public int read(ByteBuffer dst, long position) throws IOException { return channel.read(dst, position); }
        @Override public int write(ByteBuffer src) throws IOException {
            before(Operation.WRITE); return channel.write(src);
        }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            before(Operation.WRITE); return channel.write(srcs, offset, length);
        }
        @Override public int write(ByteBuffer src, long position) throws IOException {
            before(Operation.WRITE); return channel.write(src, position);
        }
        @Override public long position() throws IOException { return channel.position(); }
        @Override public FileChannel position(long position) throws IOException { channel.position(position); return this; }
        @Override public long size() throws IOException { return channel.size(); }
        @Override public FileChannel truncate(long size) throws IOException { channel.truncate(size); return this; }
        @Override public void force(boolean metadata) throws IOException { before(Operation.FORCE); channel.force(metadata); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return channel.transferTo(position, count, target);
        }
        @Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
            before(Operation.WRITE); return channel.transferFrom(source, position, count);
        }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return channel.map(mode, position, size);
        }
        @Override public FileLock lock(long position, long size, boolean shared) throws IOException {
            return channel.lock(position, size, shared);
        }
        @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return channel.tryLock(position, size, shared);
        }
        @Override protected void implCloseChannel() throws IOException { channel.close(); }
    }

    private final class WrappedPath implements Path {
        private final Path delegate;
        private WrappedPath(Path delegate) { this.delegate = delegate; }
        private Path nullable(Path path) { return path == null ? null : wrap(path); }
        @Override public FileSystem getFileSystem() { return VanishingJournalFileSystem.this; }
        @Override public boolean isAbsolute() { return delegate.isAbsolute(); }
        @Override public Path getRoot() { return nullable(delegate.getRoot()); }
        @Override public Path getFileName() { return nullable(delegate.getFileName()); }
        @Override public Path getParent() { return nullable(delegate.getParent()); }
        @Override public int getNameCount() { return delegate.getNameCount(); }
        @Override public Path getName(int index) { return wrap(delegate.getName(index)); }
        @Override public Path subpath(int begin, int end) { return wrap(delegate.subpath(begin, end)); }
        @Override public boolean startsWith(Path other) {
            return other instanceof WrappedPath wrapped && wrapped.getFileSystem() == getFileSystem()
                    && delegate.startsWith(wrapped.delegate);
        }
        @Override public boolean startsWith(String other) { return delegate.startsWith(other); }
        @Override public boolean endsWith(Path other) {
            return other instanceof WrappedPath wrapped && wrapped.getFileSystem() == getFileSystem()
                    && delegate.endsWith(wrapped.delegate);
        }
        @Override public boolean endsWith(String other) { return delegate.endsWith(other); }
        @Override public Path normalize() { return wrap(delegate.normalize()); }
        @Override public Path resolve(Path other) { return wrap(delegate.resolve(unwrapPath(other))); }
        @Override public Path resolve(String other) { return wrap(delegate.resolve(other)); }
        @Override public Path resolveSibling(Path other) { return wrap(delegate.resolveSibling(unwrapPath(other))); }
        @Override public Path resolveSibling(String other) { return wrap(delegate.resolveSibling(other)); }
        @Override public Path relativize(Path other) { return wrap(delegate.relativize(unwrapPath(other))); }
        @Override public URI toUri() {
            return URI.create(provider.getScheme() + ":" + delegate.toUri().getRawSchemeSpecificPart());
        }
        @Override public Path toAbsolutePath() { return wrap(delegate.toAbsolutePath()); }
        @Override public Path toRealPath(LinkOption... options) throws IOException {
            return wrap(delegate.toRealPath(options));
        }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                                           WatchEvent.Modifier... modifiers) throws IOException {
            throw new UnsupportedOperationException("Fixture does not expose unwrapped watch events");
        }
        @Override public Iterator<Path> iterator() {
            Iterator<Path> iterator = delegate.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Path next() { return wrap(iterator.next()); }
            };
        }
        @Override public int compareTo(Path other) { return delegate.compareTo(unwrapPath(other)); }
        @Override public boolean equals(Object other) {
            return other instanceof WrappedPath wrapped && wrapped.getFileSystem() == getFileSystem()
                    && delegate.equals(wrapped.delegate);
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(getFileSystem()) + delegate.hashCode(); }
        @Override public String toString() { return delegate.toString(); }
    }
}
