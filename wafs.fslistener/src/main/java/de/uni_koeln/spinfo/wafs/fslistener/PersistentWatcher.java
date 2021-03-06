package de.uni_koeln.spinfo.wafs.fslistener;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.apache.log4j.Logger;

import de.uni_koeln.spinfo.wafs.fslistener.FileEvent.Type;
import de.uni_koeln.spinfo.wafs.fslistener.data.PersistentDir;
import de.uni_koeln.spinfo.wafs.fslistener.data.PersistentFile;
import de.uni_koeln.spinfo.wafs.fslistener.data.PersistentFileObject;
import de.uni_koeln.spinfo.wafs.fslistener.data.UnknownFSObject;

public class PersistentWatcher {

	private File baseDir;
	
	private FileFilter acceptFilter;

	private WatchService watcher;
	
	private volatile boolean stopped = false;
	
	private Logger logger = Logger.getLogger(getClass());

	private FileEventListener[] listeners;
	
	private final PersistentDir root;
	
	private Map<WatchKey, Path> keys = new HashMap<WatchKey, Path>();
	
	private ExecutorService eventService = Executors.newSingleThreadExecutor();
	private ExecutorService registerService = Executors.newSingleThreadExecutor();

	private JAXBContext ctx;

	private File db;

	private Unmarshaller unmarshaller;

	private Marshaller marshaller;
	
	private boolean debug = logger.isDebugEnabled();
	
	private boolean modified = false;
	
	public PersistentWatcher(File baseDir, File db, FileFilter acceptFilter, FileEventListener... listeners) throws CorruptDBException, IOException {
		if(baseDir == null || !baseDir.exists() || !baseDir.isDirectory()) {
			throw new IOException("Parameter 'baseDir' must be an existing directory!");
		}
		this.baseDir = baseDir;
		this.db = db;
		try {
			ctx = JAXBContext.newInstance(PersistentDir.class, PersistentFile.class);
			unmarshaller = ctx.createUnmarshaller();
			marshaller = ctx.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		} catch (JAXBException e1) {
			throw new CorruptDBException("Failed to initialize JAXB!", e1);
		}
		if(db.exists()) {
			try {
				root = loadDB();
				logger.info("DB loaded: " + root.getNumberOfChildren() + " direct sub dirs.");
			} catch (JAXBException | IOException e) {
				throw new CorruptDBException("Failed to deserialize db!", e);
			}
		} else {
			root = new PersistentDir();
			root.setLastModified(baseDir.lastModified());
			root.setPath(baseDir.toURI());	
		}
		this.acceptFilter = acceptFilter;
		this.listeners = listeners;
		Path dir = Paths.get(baseDir.toURI());
		List<File> deleted = checkForDeletions(root);
		for (File file : deleted) {
			try {
				UnknownFSObject object = new UnknownFSObject(file);
				root.remove(object);
				fireEvent(object, Type.DELETED);
			} catch (URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		startPersistService();
		logger.info("Starting watch service...");
		startWatchService(dir);
	}

	private List<File> checkForDeletions(PersistentDir parent) {
		Collection<? extends PersistentFileObject> children = parent.getChildObjects();
		List<File> toReturn = new ArrayList<>();
		for (PersistentFileObject child : children) {
			if(child instanceof PersistentDir) {
				toReturn.addAll(checkForDeletions((PersistentDir) child));
			}
			File f = new File(child.getPath());
			if(!f.exists()) {
				toReturn.add(f);
			}
		}
		return toReturn;
	}

	private void startPersistService() {
		Thread thread = new Thread("storedb") {
			
			@Override
			public void run() {
				while(!stopped) {
					synchronized(root) {
						if(modified) {
							modified = false;
							try {
								storeDB();
								logger.info("Updated db");
							} catch (Exception e) {
								logger.error("Failed to store db", e);
							}
						}
					}
					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		};
		thread.start();
	}

	private PersistentDir loadDB() throws IOException, JAXBException {
		// New Java 7 Feature: Try-with-resources
		 try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(db), "UTF-8"))) {  
			 return (PersistentDir) unmarshaller.unmarshal(br);
		 }
	}
	
	private void storeDB() throws JAXBException, IOException {
		synchronized(root) {
			// New Java 7 Feature: Try-with-resources
			try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(db), "UTF-8"))) {
				marshaller.marshal(root, bw);
			}
		}
	}

	private void fireEvent(PersistentFileObject object, Type type) {
		synchronized (root) {
			modified = true;
		}
		final FileEvent event = new FileEvent(object, type);
		eventService.submit(new Runnable() {

			@Override
			public void run() {
				for (FileEventListener listener : listeners) {
					try {
						listener.handleEvent(event);
					} catch (Exception e) {
						logger.error("Event handler threw exception", e);
					}
				}
			}
			
		});
		if(type == Type.DELETED && object instanceof PersistentDir) {
			PersistentDir dir = (PersistentDir) object;
			Collection<? extends PersistentFileObject> children = dir.getChildObjects();
			for (PersistentFileObject child : children) {
				fireEvent(child, Type.DELETED);
			}
		}
	}
	
	private void startWatchService(final Path baseDir) throws IOException {
		watcher = FileSystems.getDefault().newWatchService();
		registerAll(baseDir);
		Thread thread = new Thread() {
			
			@Override
			public void run() {
				while(!stopped) {
				    WatchKey key;
				    try {
				        key = watcher.take();
				    } catch (InterruptedException x) {
				        return;
				    }

				    for (WatchEvent<?> event: key.pollEvents()) {
				        WatchEvent.Kind<?> kind = event.kind();

				        if (kind == StandardWatchEventKinds.OVERFLOW) {
				        	System.out.println("OVERFLOW!!");
				        	// TODO: How to handle?
				            continue;
				        }

				        // The filename is the
				        // context of the event.
				        WatchEvent<Path> ev = (WatchEvent<Path>)event;
				        Path filename = ev.context();
				        Path dir = keys.get(key);
				        Path child = dir.resolve(filename);
				        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
				        	if(kind == StandardWatchEventKinds.ENTRY_CREATE) {
								registerAll(child);
								File[] files = child.toFile().listFiles();
								for (File f : files) {
									logger.info("Checking file " + f);
									if(acceptFilter.accept(f)) {
										try {
											PersistentFileObject file = getObjectFor(f);
											fireEvent(file, Type.ADDED);
										} catch (URISyntaxException e) {
											logger.error("Failed to parse URI of " + f.getAbsolutePath() + ", skipping...");
										}
									}
								}
				        	} else if(kind == StandardWatchEventKinds.ENTRY_DELETE) {
				        		try {
									PersistentFileObject removed = null;
									synchronized(root) {
										root.remove(new UnknownFSObject(child.toFile()));
									}
									fireEvent(removed, Type.DELETED);
								} catch (URISyntaxException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
				        	}
                            continue;
                        }
			            File f = child.toFile();
			            if(!acceptFilter.accept(f)) {
			            	continue;
			            }
			            if(debug)
			            	logger.debug("Event: " + kind + " for " + child);
						try {
							if(kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
								PersistentFileObject file = getObjectFor(f);
								fireEvent(file, kind == StandardWatchEventKinds.ENTRY_CREATE ? Type.ADDED : Type.MODIFIED);
							} else {
								PersistentFileObject removed = null;
								synchronized(root) {
									removed = root.remove(new UnknownFSObject(f));
									if(removed == null) {
										removed = new UnknownFSObject(f);
									}
								}
								fireEvent(removed, Type.DELETED);
							}
						} catch (URISyntaxException e) {
							logger.error("Failed to parse URI of " + f.getAbsolutePath() + ", skipping...");
						}
				    }
				    boolean valid = key.reset();
				    if (!valid) {
				    	keys.remove(key);
				        break;
				    }
				}
			}

		};
		thread.setDaemon(false);
		thread.start();
	}
	
	 /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) {
        // register directory and sub-directories
    	registerService.submit(new Runnable() {
    		@Override
    		public void run() {
    			 try {
					Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
					        @Override
					        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
					            throws IOException
					        {
					        	logger.info("Registering: " + dir);
					            register(dir);
					            if(stopped) return FileVisitResult.TERMINATE;
					            return FileVisitResult.CONTINUE;
					        }

					    });
					storeDB();
				} catch (IOException | JAXBException e) {
					e.printStackTrace();
				}
    		}
    		
    	});
    }
    

	private PersistentFileObject getObjectFor(File f) throws URISyntaxException {
		if(f.isFile()) {
			PersistentFile file = new PersistentFile();
			synchronized (root) {
				file.setLastModified(f.lastModified());
				file.setPath(f.toURI());
				PersistentDir dir = (PersistentDir) getObjectFor(f.getParentFile());
				dir.addFile(file);
			}
			return file;
		}
		if(f.isDirectory()) {
			if(f.getAbsoluteFile().equals(baseDir.getAbsoluteFile())) {
				return root;
			}
			PersistentDir dir;
			synchronized(root) {
				dir = root.findOrCreate(f);
			}
			return dir;
		}
		return new UnknownFSObject(f);
	}
    
    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        keys.put(key, dir);
        try {
        	synchronized(root) {
        		boolean outdated = root.updateTimeStamp(dir.toFile().toURI(), dir.toFile().lastModified());
            	if(outdated) {
            		fireEvent(getObjectFor(dir.toFile()), Type.ADDED);
            	}
    			File[] children = dir.toFile().listFiles();
    			PersistentDir parentDir = root.findOrCreate(dir.toFile());
    			for (File file : children) {
    				if(stopped) return;
    				if(!file.isDirectory() && file.exists() && acceptFilter.accept(file)) {
    					PersistentFileObject oldFile = parentDir.getChildren().get(file.toURI());
    					if(oldFile == null || oldFile.getLastModified() != file.lastModified()) {
    						getObjectFor(file);
    		        		fireEvent(getObjectFor(file), Type.ADDED);
    					}
    					outdated = root.updateTimeStamp(dir.toFile().toURI(), dir.toFile().lastModified());
    		        	if(outdated) {
    		        		
    		        	}
    				}
    			}	
        	}
		} catch (URISyntaxException e) {
			logger.error("Failed to parse URI in dir " + dir + ", skipping...");
		}
    }

	public void shutdown(long timeout) throws InterruptedException {
		logger.info("Shutting down...");
		stopped = true;
		synchronized(root) {
			eventService.shutdown();
			try {
				boolean success = eventService.awaitTermination(timeout, TimeUnit.MILLISECONDS);
				logger.info("Event service shutdown friendly: " + success + ", storing data...");
			} finally {
				try {
					storeDB();
				} catch (JAXBException | IOException e) {
					logger.error("Failed to store data!", e);
				}
				logger.info("Shutdown completed");
			}
		}
	}

	/**
	 * Thread-safe method to visit all currently known files. While visiting, all modifications
	 * of the currently known file tree are blocked, such that it is guaranteed that no file will
	 * be forgotten. However, it can happen that the file tree will be modified immediately after
	 * returning from this method. In such a case, the registered {@link FileEventListener}s will
	 * be informed.
	 * @param fileVisitor
	 * @return
	 */
	public boolean visitKnownFiles(FileVisitor fileVisitor) {
		synchronized (root) {
			Collection<? extends PersistentFileObject> children = root.getChildObjects();
			for (PersistentFileObject child : children) {
				if(child instanceof PersistentFile) {
					if(!fileVisitor.visit((PersistentFile) child)) {
						return false;
					}
				} else {
					boolean shouldContinue = visitKnownFiles(fileVisitor);
					if(!shouldContinue) {
						return false;
					}
				}
			}
			return true;
		}
	}

}
