/* ==================================================================
 * DefaultBackupManager.java - Mar 27, 2013 9:17:24 AM
 * 
 * Copyright 2007-2013 SolarNetwork.net Dev Team
 * 
 * This program is free software; you can redistribute it and/or 
 * modify it under the terms of the GNU General Public License as 
 * published by the Free Software Foundation; either version 2 of 
 * the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with this program; if not, write to the Free Software 
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 
 * 02111-1307 USA
 * ==================================================================
 */

package net.solarnetwork.node.backup;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import net.solarnetwork.node.settings.SettingSpecifier;
import net.solarnetwork.node.settings.support.BasicRadioGroupSettingSpecifier;
import net.solarnetwork.node.util.PrefixedMessageSource;
import net.solarnetwork.util.OptionalService;
import net.solarnetwork.util.UnionIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.HierarchicalMessageSource;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.util.FileCopyUtils;

/**
 * Default implementation of {@link BackupManager}.
 * 
 * <p>
 * The configurable properties of this class are:
 * </p>
 * 
 * <dl class="class-properties">
 * <dt>backupServiceTracker</dt>
 * <dd>A tracker for the desired backup service to use.</dd>
 * 
 * <dt>resourceProviders</dt>
 * <dd>The collection of {@link BackupResourceProvider} instances that provide
 * the resources to be backed up.</dd>
 * </dl>
 * 
 * @author matt
 * @version 1.0
 */
public class DefaultBackupManager implements BackupManager {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private Collection<BackupService> backupServices;
	private OptionalService<BackupService> backupServiceTracker;
	private Collection<BackupResourceProvider> resourceProviders;
	private ExecutorService executorService = defaultExecutorService();

	private static HierarchicalMessageSource getMessageSourceInstance() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBundleClassLoader(DefaultBackupManager.class.getClassLoader());
		source.setBasename(DefaultBackupManager.class.getName());
		return source;
	}

	private static ExecutorService defaultExecutorService() {
		// we want at most one backup happening at a time by default
		return new ThreadPoolExecutor(0, 1, 5, TimeUnit.MINUTES, new ArrayBlockingQueue<Runnable>(3,
				true));
	}

	@Override
	public String getSettingUID() {
		return getClass().getName();
	}

	@Override
	public String getDisplayName() {
		return "Backup Manager";
	}

	@Override
	public MessageSource getMessageSource() {
		HierarchicalMessageSource source = getMessageSourceInstance();
		HierarchicalMessageSource child = source;
		for ( BackupService backupService : backupServices ) {
			PrefixedMessageSource ps = new PrefixedMessageSource();
			ps.setDelegate(backupService.getSettingSpecifierProvider().getMessageSource());
			ps.setPrefix(backupService.getKey() + ".");
			child.setParentMessageSource(ps);
			child = ps;
		}

		return source;
	}

	@Override
	public List<SettingSpecifier> getSettingSpecifiers() {
		List<SettingSpecifier> results = new ArrayList<SettingSpecifier>(20);
		BasicRadioGroupSettingSpecifier serviceSpec = new BasicRadioGroupSettingSpecifier(
				"backupServiceTracker.propertyFilters['key']", FileSystemBackupService.KEY);
		Map<String, String> serviceSpecValues = new TreeMap<String, String>();
		for ( BackupService service : backupServices ) {
			serviceSpecValues.put(service.getKey(), service.getSettingSpecifierProvider()
					.getDisplayName());
		}
		serviceSpec.setValueTitles(serviceSpecValues);
		results.add(serviceSpec);
		return results;
	}

	@Override
	public BackupService activeBackupService() {
		return backupServiceTracker.service();
	}

	@Override
	public Iterable<BackupResource> resourcesForBackup() {
		BackupService service = activeBackupService();
		if ( service == null ) {
			log.debug("No BackupService available, can't find resources for backup");
			return Collections.emptyList();
		}
		if ( service.getInfo().getStatus() != BackupStatus.Configured ) {
			log.info("BackupService {} in {} state, can't find resources for backup", service.getKey(),
					service.getInfo().getStatus());
			return Collections.emptyList();
		}

		final List<Iterator<BackupResource>> resources = new ArrayList<Iterator<BackupResource>>(10);
		for ( BackupResourceProvider provider : resourceProviders ) {
			// map each resource into a sub directory
			Iterator<BackupResource> itr = provider.getBackupResources().iterator();
			resources.add(new PrefixedBackupResourceIterator(itr, provider.getKey()));

		}
		return new Iterable<BackupResource>() {

			@Override
			public Iterator<BackupResource> iterator() {
				return new UnionIterator<BackupResource>(resources);
			}

		};
	}

	@Override
	public Backup createBackup() {
		final BackupService service = activeBackupService();
		if ( service == null ) {
			log.debug("No active backup service available, cannot perform backup");
			return null;
		}
		final BackupServiceInfo info = service.getInfo();
		final BackupStatus status = info.getStatus();
		if ( status != BackupStatus.Configured ) {
			log.info("BackupService {} is in the {} state; cannot perform backup", service.getKey(),
					status);
			return null;
		}

		log.info("Initiating backup to service {}", service.getKey());
		final Backup backup = service.performBackup(resourcesForBackup());
		if ( backup != null ) {
			log.info("Backup {} {} with service {}", backup.getKey(), (backup.isComplete() ? "completed"
					: "initiated"), service.getKey());
		}
		return backup;
	}

	@Override
	public Future<Backup> createAsynchronousBackup() {
		assert executorService != null;
		return executorService.submit(new Callable<Backup>() {

			@Override
			public Backup call() throws Exception {
				return createBackup();
			}

		});
	}

	@Override
	public void exportBackupArchive(String backupKey, OutputStream out) throws IOException {
		final BackupService service = activeBackupService();
		if ( service == null ) {
			return;
		}

		final Backup backup = service.backupForKey(backupKey);
		if ( backup == null ) {
			return;
		}

		// create the zip archive for the backup files
		ZipOutputStream zos = new ZipOutputStream(out);
		try {
			BackupResourceIterable resources = service.getBackupResources(backup);
			for ( BackupResource r : resources ) {
				zos.putNextEntry(new ZipEntry(r.getBackupPath()));
				FileCopyUtils.copy(r.getInputStream(), new FilterOutputStream(zos) {

					@Override
					public void close() throws IOException {
						// FileCopyUtils closed the stream, which we don't want here
					}

				});
			}
			resources.close();
		} finally {
			zos.flush();
			zos.finish();
			zos.close();
		}
	}

	@Override
	public void importBackupArchive(InputStream archive) throws IOException {
		final ZipInputStream zin = new ZipInputStream(archive);
		while ( true ) {
			final ZipEntry entry = zin.getNextEntry();
			if ( entry == null ) {
				break;
			}
			final String path = entry.getName();
			log.debug("Restoring backup resource {}", path);
			final int providerIndex = path.indexOf('/');
			if ( providerIndex != -1 ) {
				final String providerKey = path.substring(0, providerIndex);
				for ( BackupResourceProvider provider : resourceProviders ) {
					if ( providerKey.equals(provider.getKey()) ) {
						provider.restoreBackupResource(new BackupResource() {

							@Override
							public String getBackupPath() {
								return path.substring(providerIndex + 1);
							}

							@Override
							public InputStream getInputStream() throws IOException {
								return new FilterInputStream(zin) {

									@Override
									public void close() throws IOException {
										// don't close me
									}

								};
							}

							@Override
							public long getModificationDate() {
								return entry.getTime();
							}

						});
						break;
					}
				}
			}
		}
	}

	@Override
	public void restoreBackup(Backup backup) {
		BackupService service = backupServiceTracker.service();
		if ( service == null ) {
			return;
		}
		BackupResourceIterable resources = service.getBackupResources(backup);
		try {
			for ( final BackupResource r : resources ) {
				// top-level dir is the  key of the provider
				final String path = r.getBackupPath();
				log.debug("Restoring backup {} resource {}", backup.getKey(), path);
				final int providerIndex = path.indexOf('/');
				if ( providerIndex != -1 ) {
					final String providerKey = path.substring(0, providerIndex);
					for ( BackupResourceProvider provider : resourceProviders ) {
						if ( providerKey.equals(provider.getKey()) ) {
							provider.restoreBackupResource(new BackupResource() {

								@Override
								public String getBackupPath() {
									return path.substring(providerIndex + 1);
								}

								@Override
								public InputStream getInputStream() throws IOException {
									return r.getInputStream();
								}

								@Override
								public long getModificationDate() {
									return r.getModificationDate();
								}

							});
							break;
						}
					}
				}
			}
		} finally {
			try {
				resources.close();
			} catch ( IOException e ) {
				// ignore
			}
		}
	}

	private static class PrefixedBackupResourceIterator implements Iterator<BackupResource> {

		private final Iterator<BackupResource> delegate;
		private final String prefix;

		private PrefixedBackupResourceIterator(Iterator<BackupResource> delegate, String prefix) {
			super();
			this.delegate = delegate;
			this.prefix = prefix;
		}

		@Override
		public boolean hasNext() {
			return delegate.hasNext();
		}

		@Override
		public BackupResource next() {
			final BackupResource r = delegate.next();
			return new BackupResource() {

				@Override
				public String getBackupPath() {
					return prefix + '/' + r.getBackupPath();
				}

				@Override
				public InputStream getInputStream() throws IOException {
					return r.getInputStream();
				}

				@Override
				public long getModificationDate() {
					return r.getModificationDate();
				}

			};
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	public void setBackupServiceTracker(OptionalService<BackupService> backupServiceTracker) {
		this.backupServiceTracker = backupServiceTracker;
	}

	public void setBackupServices(Collection<BackupService> backupServices) {
		this.backupServices = backupServices;
	}

	public void setResourceProviders(Collection<BackupResourceProvider> resourceProviders) {
		this.resourceProviders = resourceProviders;
	}

	public void setExecutorService(ExecutorService executorService) {
		this.executorService = executorService;
	}

}
