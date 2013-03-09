/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.container;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.osgi.container.Module.StartOptions;
import org.eclipse.osgi.container.Module.State;
import org.eclipse.osgi.container.Module.StopOptions;
import org.eclipse.osgi.container.ModuleContainerAdaptor.ContainerEvent;
import org.eclipse.osgi.container.ModuleContainerAdaptor.ModuleEvent;
import org.eclipse.osgi.container.ModuleDatabase.Sort;
import org.eclipse.osgi.container.ModuleRequirement.DynamicModuleRequirement;
import org.eclipse.osgi.framework.eventmgr.*;
import org.eclipse.osgi.framework.util.SecureAction;
import org.eclipse.osgi.internal.container.LockSet;
import org.osgi.framework.*;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.resolver.ResolutionException;

/**
 * A container for installing, updating, uninstalling and resolve modules.
 * @since 3.10
 */
public final class ModuleContainer {
	private final static SecureAction secureAction = AccessController.doPrivileged(SecureAction.createSecureAction());

	/**
	 * Used by install operations to establish a write lock on an install location
	 */
	private final LockSet<String> locationLocks = new LockSet<String>();

	/**
	 * Used by install and update operations to establish a write lock for a name
	 */
	private final LockSet<String> nameLocks = new LockSet<String>();

	/**
	 * An implementation of FrameworkWiring for this container
	 */
	private final ContainerWiring frameworkWiring;

	/**
	 * An implementation of FrameworkStartLevel for this container
	 */
	private final ContainerStartLevel frameworkStartLevel;

	/**
	 * The module database for this container.
	 */
	final ModuleDatabase moduleDatabase;

	/**
	 * The module adaptor for this container.
	 */
	final ModuleContainerAdaptor adaptor;

	/**
	 * The module resolver which implements the ResolverContext and handles calling the 
	 * resolver service.
	 */
	private final ModuleResolver moduleResolver;

	private final Collection<SystemModule> refreshingSystemModule = new ArrayList<SystemModule>(1);

	/**
	 * Constructs a new container with the specified collision hook, resolver hook, resolver and module database.
	 * @param adaptor the adaptor for the container
	 * @param moduledataBase the module database
	 */
	public ModuleContainer(ModuleContainerAdaptor adaptor, ModuleDatabase moduledataBase) {
		this.adaptor = adaptor;
		this.moduleResolver = new ModuleResolver(adaptor);
		this.moduleDatabase = moduledataBase;
		this.frameworkWiring = new ContainerWiring();
		this.frameworkStartLevel = new ContainerStartLevel();
	}

	/**
	 * Returns the adaptor for this container
	 * @return the adaptor for this container
	 */
	public ModuleContainerAdaptor getAdaptor() {
		return adaptor;
	}

	/**
	 * Returns the list of currently installed modules sorted by module id.
	 * @return the list of currently installed modules sorted by module id.
	 */
	public List<Module> getModules() {
		return moduleDatabase.getModules();
	}

	/**
	 * Returns the module installed with the specified id, or null if no 
	 * such module is installed.
	 * @param id the id of the module
	 * @return the module with the specified id, or null of no such module is installed.
	 */
	public Module getModule(long id) {
		return moduleDatabase.getModule(id);
	}

	/**
	 * Returns the module installed with the specified location, or null if no 
	 * such module is installed.
	 * @param location the location of the module
	 * @return the module with the specified location, or null of no such module is installed.
	 */
	public Module getModule(String location) {
		return moduleDatabase.getModule(location);
	}

	/**
	 * Returns a snapshot collection of revisions with the specified name 
	 * and version.  If version is {@code null} then all revisions with
	 * the specified name are returned.
	 * @param name the name of the modules
	 * @param version the version of the modules or {@code null}
	 * @return a snapshot collection of revisions with the specified name
	 * and version.
	 */
	public Collection<ModuleRevision> getRevisions(String name, Version version) {
		return moduleDatabase.getRevisions(name, version);
	}

	/**
	 * Installs a new module using the specified location.  The specified
	 * builder is used to create a new {@link ModuleRevision revision} 
	 * which will become the {@link Module#getCurrentRevision() current}
	 * revision of the new module.
	 * <p>
	 * If a module already exists with the specified location then the 
	 * existing module is returned and the builder is not used.
	 * @param origin the module performing the install, may be {@code null}.
	 * @param location The location identifier of the module to install. 
	 * @param builder the builder used to create the revision to install.
	 * @param revisionInfo the revision info for the new revision, may be {@code null}.
	 * @return a new module or a existing module if one exists at the 
	 *     specified location.
	 * @throws BundleException if some error occurs installing the module
	 */
	public Module install(Module origin, String location, ModuleRevisionBuilder builder, Object revisionInfo) throws BundleException {
		String name = builder.getSymbolicName();
		boolean locationLocked = false;
		boolean nameLocked = false;
		try {
			// Attempt to lock the location and name
			try {
				locationLocked = locationLocks.tryLock(location, 5, TimeUnit.SECONDS);
				nameLocked = name != null && nameLocks.tryLock(name, 5, TimeUnit.SECONDS);
				if (!locationLocked) {
					throw new BundleException("Failed to obtain location lock for installation: " + location, BundleException.STATECHANGE_ERROR);
				}
				if (name != null && !nameLocked) {
					throw new BundleException("Failed to obtain symbolic name lock for installation: " + name, BundleException.STATECHANGE_ERROR);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new BundleException("Failed to obtain id locks for installation.", BundleException.STATECHANGE_ERROR, e);
			}

			Module existingLocation = null;
			Collection<Module> collisionCandidates = Collections.emptyList();
			moduleDatabase.readLock();
			try {
				existingLocation = moduleDatabase.getModule(location);
				if (existingLocation == null) {
					// Collect existing current revisions with the same name and version as the revision we want to install
					// This is to perform the collision check below
					Collection<ModuleRevision> existingRevisionNames = moduleDatabase.getRevisions(name, builder.getVersion());
					if (!existingRevisionNames.isEmpty()) {
						collisionCandidates = new ArrayList<Module>(1);
						for (ModuleRevision equinoxRevision : existingRevisionNames) {
							if (!equinoxRevision.isCurrent())
								continue; // only pay attention to current revisions
							// need to prevent duplicates here; this is in case a revisions object contains multiple revision objects.
							if (!collisionCandidates.contains(equinoxRevision.getRevisions().getModule()))
								collisionCandidates.add(equinoxRevision.getRevisions().getModule());
						}
					}
				}
			} finally {
				moduleDatabase.readUnlock();
			}
			// Check that the existing location is visible from the origin module
			if (existingLocation != null) {
				if (origin != null) {
					Bundle bundle = origin.getBundle();
					BundleContext context = bundle == null ? null : bundle.getBundleContext();
					if (context != null && context.getBundle(existingLocation.getId()) == null) {
						Bundle b = existingLocation.getBundle();
						throw new BundleException("Bundle \"" + b.getSymbolicName() + "\" version \"" + b.getVersion() + "\" is already installed at location: " + location, BundleException.REJECTED_BY_HOOK);
					}
				}
				return existingLocation;
			}
			// Check that the bundle does not collide with other bundles with the same name and version
			// This is from the perspective of the origin bundle
			if (origin != null && !collisionCandidates.isEmpty()) {
				adaptor.getModuleCollisionHook().filterCollisions(ModuleCollisionHook.INSTALLING, origin, collisionCandidates);
			}
			if (!collisionCandidates.isEmpty()) {
				throw new BundleException("A bundle is already installed with name \"" + name + "\" and version \"" + builder.getVersion(), BundleException.DUPLICATE_BUNDLE_ERROR);
			}

			Module result = moduleDatabase.install(location, builder, revisionInfo);

			adaptor.publishModuleEvent(ModuleEvent.INSTALLED, result, origin);

			return result;
		} finally {
			if (locationLocked)
				locationLocks.unlock(location);
			if (nameLocked)
				nameLocks.unlock(name);
		}
	}

	/**
	 * Updates the specified module with a new revision.  The specified
	 * builder is used to create a new {@link ModuleRevision revision} 
	 * which will become the {@link Module#getCurrentRevision() current}
	 * revision of the new module.
	 * @param module the module to update
	 * @param builder the builder used to create the revision for the update.
	 * @param revisionInfo the revision info for the new revision, may be {@code null}.
	 * @throws BundleException if some error occurs updating the module
	 */
	public void update(Module module, ModuleRevisionBuilder builder, Object revisionInfo) throws BundleException {
		checkAdminPermission(module.getBundle(), AdminPermission.LIFECYCLE);
		String name = builder.getSymbolicName();
		boolean nameLocked = false;
		try {
			// Attempt to lock the name
			try {
				if (name != null && !(nameLocked = nameLocks.tryLock(name, 5, TimeUnit.SECONDS))) {
					throw new BundleException("Failed to obtain id locks for installation.", BundleException.STATECHANGE_ERROR);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new BundleException("Failed to obtain id locks for installation.", BundleException.STATECHANGE_ERROR, e);
			}

			Collection<Module> collisionCandidates = Collections.emptyList();
			moduleDatabase.readLock();
			try {
				// Collect existing bundles with the same name and version as the bundle we want to install
				// This is to perform the collision check below
				Collection<ModuleRevision> existingRevisionNames = moduleDatabase.getRevisions(name, builder.getVersion());
				if (!existingRevisionNames.isEmpty()) {
					collisionCandidates = new ArrayList<Module>(1);
					for (ModuleRevision equinoxRevision : existingRevisionNames) {
						if (!equinoxRevision.isCurrent())
							continue;
						Module m = equinoxRevision.getRevisions().getModule();
						if (m.equals(module))
							continue; // don't worry about the updating modules revisions
						// need to prevent duplicates here; this is in case a revisions object contains multiple revision objects.
						if (!collisionCandidates.contains(m))
							collisionCandidates.add(m);
					}
				}

			} finally {
				moduleDatabase.readUnlock();
			}

			// Check that the module does not collide with other modules with the same name and version
			// This is from the perspective of the module being updated
			if (module != null && !collisionCandidates.isEmpty()) {
				adaptor.getModuleCollisionHook().filterCollisions(ModuleCollisionHook.UPDATING, module, collisionCandidates);
			}

			if (!collisionCandidates.isEmpty()) {
				throw new BundleException("A bundle is already installed with name \"" + name + "\" and version \"" + builder.getVersion(), BundleException.DUPLICATE_BUNDLE_ERROR);
			}

			module.lockStateChange(ModuleEvent.UPDATED);
			State previousState;
			try {
				module.checkValid();
				previousState = module.getState();
				if (Module.ACTIVE_SET.contains(previousState)) {
					// throwing an exception from stop terminates update
					module.stop(StopOptions.TRANSIENT);
				}
				if (Module.RESOLVED_SET.contains(previousState)) {
					// set the state to installed and publish unresolved event
					module.setState(State.INSTALLED);
					adaptor.publishModuleEvent(ModuleEvent.UNRESOLVED, module, module);
				}
				moduleDatabase.update(module, builder, revisionInfo);
			} finally {
				module.unlockStateChange(ModuleEvent.UPDATED);
			}
			// only publish updated event on success
			adaptor.publishModuleEvent(ModuleEvent.UPDATED, module, module);

			if (Module.ACTIVE_SET.contains(previousState)) {
				try {
					// restart the module if necessary
					module.start(StartOptions.TRANSIENT_RESUME);
				} catch (BundleException e) {
					getAdaptor().publishContainerEvent(ContainerEvent.ERROR, module, e);
				}
			}
		} finally {
			if (nameLocked)
				nameLocks.unlock(name);
		}
	}

	/**
	 * Uninstalls the specified module.
	 * @param module the module to uninstall
	 * @throws BundleException if some error occurs uninstalling the module
	 */
	public void uninstall(Module module) throws BundleException {
		checkAdminPermission(module.getBundle(), AdminPermission.LIFECYCLE);
		module.lockStateChange(ModuleEvent.UNINSTALLED);
		State previousState;
		try {
			module.checkValid();
			previousState = module.getState();
			if (Module.ACTIVE_SET.contains(module.getState())) {
				try {
					module.stop(StopOptions.TRANSIENT);
				} catch (BundleException e) {
					adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e);
				}
			}
			if (Module.RESOLVED_SET.contains(previousState)) {
				// set the state to installed and publish unresolved event
				module.setState(State.INSTALLED);
				adaptor.publishModuleEvent(ModuleEvent.UNRESOLVED, module, module);
			}
			moduleDatabase.uninstall(module);
			module.setState(State.UNINSTALLED);
		} finally {
			module.unlockStateChange(ModuleEvent.UNINSTALLED);
		}
		adaptor.publishModuleEvent(ModuleEvent.UNINSTALLED, module, module);
	}

	ModuleWiring getWiring(ModuleRevision revision) {
		return moduleDatabase.getWiring(revision);
	}

	/**
	 * Returns the {@link FrameworkWiring} for this container
	 * @return the framework wiring for this container.
	 */
	public FrameworkWiring getFrameworkWiring() {
		return frameworkWiring;
	}

	/**
	 * Returns the {@link FrameworkStartLevel} for this container
	 * @return the framework start level for this container
	 */
	public FrameworkStartLevel getFrameworkStartLevel() {
		return frameworkStartLevel;
	}

	/**
	 * Attempts to resolve the current revisions of the specified modules.
	 * @param triggers the modules to resolve or {@code null} to resolve all unresolved
	 *    current revisions.
	 * @param triggersMandatory true if the triggers must be resolved.  This will result in 
	 *   a {@link ResolutionException} if set to true and one of the triggers could not be resolved.
	 * @throws ResolutionException if a resolution error occurs
	 * @see FrameworkWiring#resolveBundles(Collection)
	 */
	public void resolve(Collection<Module> triggers, boolean triggersMandatory) throws ResolutionException {
		resolve(triggers, triggersMandatory, false);
	}

	private void resolve(Collection<Module> triggers, boolean triggersMandatory, boolean restartTriggers) throws ResolutionException {
		if (isRefreshingSystemModule()) {
			throw new ResolutionException("Unable to resolve while shutting down the framework.");
		}
		try {
			while (!resolveAndApply(triggers, triggersMandatory, restartTriggers)) {
				// nothing
			}
		} catch (RuntimeException e) {
			if (e.getCause() instanceof BundleException) {
				throw new ResolutionException(e.getCause());
			}
			throw e;
		}
	}

	private boolean resolveAndApply(Collection<Module> triggers, boolean triggersMandatory, boolean restartTriggers) throws ResolutionException {
		if (triggers == null)
			triggers = new ArrayList<Module>(0);
		Collection<ModuleRevision> triggerRevisions = new ArrayList<ModuleRevision>(triggers.size());
		Collection<ModuleRevision> unresolved = new ArrayList<ModuleRevision>();
		Map<ModuleRevision, ModuleWiring> wiringClone;
		long timestamp;
		moduleDatabase.readLock();
		try {
			timestamp = moduleDatabase.getRevisionsTimestamp();
			wiringClone = moduleDatabase.getWiringsClone();
			for (Module module : triggers) {
				if (!State.UNINSTALLED.equals(module.getState())) {
					ModuleRevision current = module.getCurrentRevision();
					if (current != null)
						triggerRevisions.add(current);
				}
			}
			Collection<Module> allModules = moduleDatabase.getModules();
			for (Module module : allModules) {
				ModuleRevision revision = module.getCurrentRevision();
				if (revision != null && !wiringClone.containsKey(revision))
					unresolved.add(revision);
			}
		} finally {
			moduleDatabase.readUnlock();
		}

		Map<ModuleRevision, ModuleWiring> deltaWiring = moduleResolver.resolveDelta(triggerRevisions, triggersMandatory, unresolved, wiringClone, moduleDatabase);
		if (deltaWiring.isEmpty())
			return true; // nothing to do

		Collection<Module> modulesResolved = new ArrayList<Module>();
		for (ModuleRevision deltaRevision : deltaWiring.keySet()) {
			if (!wiringClone.containsKey(deltaRevision))
				modulesResolved.add(deltaRevision.getRevisions().getModule());
		}

		return applyDelta(deltaWiring, modulesResolved, triggers, timestamp, restartTriggers);
	}

	public ModuleWire resolveDynamic(String dynamicPkgName, ModuleRevision revision) throws ResolutionException {
		ModuleWire result;
		Map<ModuleRevision, ModuleWiring> deltaWiring;
		Collection<Module> modulesResolved;
		long timestamp;
		do {
			result = null;
			Map<ModuleRevision, ModuleWiring> wiringClone = null;
			List<DynamicModuleRequirement> dynamicReqs = null;
			Collection<ModuleRevision> unresolved = new ArrayList<ModuleRevision>();
			moduleDatabase.readLock();
			try {
				dynamicReqs = getDynamicRequirements(dynamicPkgName, revision);
				if (dynamicReqs.isEmpty()) {
					// do nothing
					return null;
				}
				timestamp = moduleDatabase.getRevisionsTimestamp();
				wiringClone = moduleDatabase.getWiringsClone();
				Collection<Module> allModules = moduleDatabase.getModules();
				for (Module module : allModules) {
					ModuleRevision current = module.getCurrentRevision();
					if (current != null && !wiringClone.containsKey(current))
						unresolved.add(current);
				}
			} finally {
				moduleDatabase.readUnlock();
			}

			deltaWiring = null;
			for (DynamicModuleRequirement dynamicReq : dynamicReqs) {
				deltaWiring = moduleResolver.resolveDynamicDelta(dynamicReq, unresolved, wiringClone, moduleDatabase);
				if (deltaWiring.get(revision) != null) {
					break;
				}
			}
			if (deltaWiring == null || deltaWiring.get(revision) == null)
				return null; // nothing to do

			modulesResolved = new ArrayList<Module>();
			for (ModuleRevision deltaRevision : deltaWiring.keySet()) {
				if (!wiringClone.containsKey(deltaRevision))
					modulesResolved.add(deltaRevision.getRevisions().getModule());
			}

			// Save the result
			ModuleWiring wiring = deltaWiring.get(revision);
			if (wiring != null) {
				List<ModuleWire> wires = wiring.getRequiredModuleWires(null);
				result = wires.isEmpty() ? null : wires.get(wires.size() - 1);
				// Doing a sanity check, may not be necessary
				if (result != null) {
					if (!PackageNamespace.PACKAGE_NAMESPACE.equals(result.getCapability().getNamespace()) || !dynamicPkgName.equals(result.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE))) {
						throw new ResolutionException("Resolver provided an invalid dynamic wire: " + result);
					}
				}
			}
		} while (!applyDelta(deltaWiring, modulesResolved, Collections.<Module> emptyList(), timestamp, false));

		return result;
	}

	private boolean applyDelta(Map<ModuleRevision, ModuleWiring> deltaWiring, Collection<Module> modulesResolved, Collection<Module> triggers, long timestamp, boolean restartTriggers) {
		List<Module> modulesLocked = new ArrayList<Module>(modulesResolved.size());
		// now attempt to apply the delta
		try {
			// acquire the necessary RESOLVED state change lock
			for (Module module : modulesResolved) {
				try {
					module.lockStateChange(ModuleEvent.RESOLVED);
					modulesLocked.add(module);
				} catch (BundleException e) {
					// TODO throw some appropriate exception
					throw new IllegalStateException("Could not acquire state change lock.", e);
				}
			}
			moduleDatabase.writeLock();
			try {
				if (timestamp != moduleDatabase.getRevisionsTimestamp())
					return false; // need to try again
				Map<ModuleRevision, ModuleWiring> wiringCopy = moduleDatabase.getWiringsCopy();
				for (Map.Entry<ModuleRevision, ModuleWiring> deltaEntry : deltaWiring.entrySet()) {
					ModuleWiring current = wiringCopy.get(deltaEntry.getKey());
					if (current != null) {
						// need to update the provided capabilities, provided and required wires for currently resolved
						current.setCapabilities(deltaEntry.getValue().getModuleCapabilities(null));
						current.setProvidedWires(deltaEntry.getValue().getProvidedModuleWires(null));
						current.setRequiredWires(deltaEntry.getValue().getRequiredModuleWires(null));
						deltaEntry.setValue(current); // set the real wiring into the delta
					} else {
						modulesResolved.add(deltaEntry.getValue().getRevision().getRevisions().getModule());
					}
				}
				moduleDatabase.mergeWiring(deltaWiring);
				moduleDatabase.sortModules(modulesLocked, Sort.BY_DEPENDENCY, Sort.BY_START_LEVEL);
			} finally {
				moduleDatabase.writeUnlock();
			}
			// set the modules state to resolved
			for (Module module : modulesLocked) {
				module.setState(State.RESOLVED);
			}
		} finally {
			for (Module module : modulesLocked) {
				module.unlockStateChange(ModuleEvent.RESOLVED);
			}
		}

		for (Module module : modulesLocked) {
			adaptor.publishModuleEvent(ModuleEvent.RESOLVED, module, module);
		}

		// If there are any triggers re-start them now if requested
		Set<Module> triggerSet = restartTriggers ? new HashSet<Module>(triggers) : Collections.<Module> emptySet();
		if (restartTriggers) {
			for (Module module : triggers) {
				try {
					if (module.getId() != 0 && Module.RESOLVED_SET.contains(module.getState())) {
						secureAction.start(module, StartOptions.TRANSIENT);
					}
				} catch (BundleException e) {
					adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e);
				}
			}
		}
		// This is questionable behavior according to the spec but this was the way equinox previously behaved
		// Need to auto-start any persistently started bundles that got resolved
		for (Module module : modulesLocked) {
			if (module.holdsTransitionEventLock(ModuleEvent.STARTED) || module.getId() == 0 || triggerSet.contains(module)) {
				continue;
			}
			try {
				secureAction.start(module, StartOptions.TRANSIENT_IF_AUTO_START, StartOptions.TRANSIENT_RESUME);
			} catch (BundleException e) {
				adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e);
			}
		}
		return true;
	}

	private List<DynamicModuleRequirement> getDynamicRequirements(String dynamicPkgName, ModuleRevision revision) {
		// TODO Will likely need to optimize this
		if ((revision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) {
			// only do this for hosts
			return null;
		}
		ModuleWiring wiring = revision.getWiring();
		if (wiring == null) {
			// not resolved!
			return null;
		}
		List<DynamicModuleRequirement> result = new ArrayList<ModuleRequirement.DynamicModuleRequirement>(1);
		// check the dynamic import packages
		DynamicModuleRequirement dynamicRequirement;
		for (ModuleRequirement requirement : wiring.getModuleRequirements(PackageNamespace.PACKAGE_NAMESPACE)) {
			dynamicRequirement = requirement.getDynamicPackageRequirement(revision, dynamicPkgName);
			if (dynamicRequirement != null) {
				result.add(dynamicRequirement);
			}
		}

		return result;
	}

	private Collection<Module> unresolve(Collection<Module> initial) {
		Collection<Module> refreshTriggers = null;
		while (refreshTriggers == null) {
			refreshTriggers = unresolve0(initial);
		}
		return refreshTriggers;
	}

	private Collection<Module> unresolve0(Collection<Module> initial) {
		Map<ModuleRevision, ModuleWiring> wiringCopy;
		List<Module> refreshTriggers;
		Collection<ModuleRevision> toRemoveRevisions;
		Collection<ModuleWiring> toRemoveWirings;
		Map<ModuleWiring, Collection<ModuleWire>> toRemoveWireLists;
		long timestamp;
		moduleDatabase.readLock();
		try {
			checkSystemExtensionRefresh(initial);
			timestamp = moduleDatabase.getRevisionsTimestamp();
			wiringCopy = moduleDatabase.getWiringsCopy();
			refreshTriggers = new ArrayList<Module>(getRefreshClosure(initial, wiringCopy));
			toRemoveRevisions = new ArrayList<ModuleRevision>();
			toRemoveWirings = new ArrayList<ModuleWiring>();
			toRemoveWireLists = new HashMap<ModuleWiring, Collection<ModuleWire>>();
			for (Iterator<Module> iTriggers = refreshTriggers.iterator(); iTriggers.hasNext();) {
				Module module = iTriggers.next();
				boolean first = true;
				for (ModuleRevision revision : module.getRevisions().getModuleRevisions()) {
					ModuleWiring removedWiring = wiringCopy.remove(revision);
					if (removedWiring != null) {
						toRemoveWirings.add(removedWiring);
						List<ModuleWire> removedWires = removedWiring.getRequiredModuleWires(null);
						for (ModuleWire wire : removedWires) {
							Collection<ModuleWire> providerWires = toRemoveWireLists.get(wire.getProviderWiring());
							if (providerWires == null) {
								providerWires = new ArrayList<ModuleWire>();
								toRemoveWireLists.put(wire.getProviderWiring(), providerWires);
							}
							providerWires.add(wire);
						}
					}
					if (!first || revision.getRevisions().isUninstalled()) {
						toRemoveRevisions.add(revision);
					}
					first = false;
				}
				if (module.getState().equals(State.UNINSTALLED)) {
					iTriggers.remove();
				}
			}
			moduleDatabase.sortModules(refreshTriggers, Sort.BY_START_LEVEL, Sort.BY_DEPENDENCY);
		} finally {
			moduleDatabase.readUnlock();
		}

		Module systemModule = moduleDatabase.getModule(0);
		if (refreshTriggers.contains(systemModule) && Module.ACTIVE_SET.contains(systemModule.getState())) {
			refreshSystemModule();
			return Collections.emptyList();
		}
		Collection<Module> modulesLocked = new ArrayList<Module>(refreshTriggers.size());
		Collection<Module> modulesUnresolved = new ArrayList<Module>();
		try {
			// acquire module state change locks
			try {
				// go in reverse order
				for (ListIterator<Module> iTriggers = refreshTriggers.listIterator(refreshTriggers.size()); iTriggers.hasPrevious();) {
					Module refreshModule = iTriggers.previous();
					refreshModule.lockStateChange(ModuleEvent.UNRESOLVED);
					modulesLocked.add(refreshModule);
				}
			} catch (BundleException e) {
				// TODO throw some appropriate exception
				throw new IllegalStateException("Could not acquire state change lock.", e);
			}
			// Stop any active bundles and remove non-active modules from the refreshTriggers
			for (ListIterator<Module> iTriggers = refreshTriggers.listIterator(refreshTriggers.size()); iTriggers.hasPrevious();) {
				Module refreshModule = iTriggers.previous();
				State previousState = refreshModule.getState();
				if (Module.ACTIVE_SET.contains(previousState)) {
					try {
						refreshModule.stop(StopOptions.TRANSIENT);
					} catch (BundleException e) {
						adaptor.publishContainerEvent(ContainerEvent.ERROR, refreshModule, e);
					}
				}
				if (!State.ACTIVE.equals(previousState)) {
					iTriggers.remove();
				}
			}

			// do a sanity check on states of the modules, they must be INSTALLED, RESOLVED or UNINSTALLED
			for (Module module : modulesLocked) {
				if (Module.ACTIVE_SET.contains(module.getState())) {
					throw new IllegalStateException("Module is in the wrong state: " + module + ": " + module.getState());
				}
			}

			// finally apply the unresolve to the database
			moduleDatabase.writeLock();
			try {
				if (timestamp != moduleDatabase.getRevisionsTimestamp())
					return null; // need to try again
				// remove any wires from unresolved wirings that got removed
				for (Map.Entry<ModuleWiring, Collection<ModuleWire>> entry : toRemoveWireLists.entrySet()) {
					List<ModuleWire> provided = entry.getKey().getProvidedModuleWires(null);
					provided.removeAll(entry.getValue());
					entry.getKey().setProvidedWires(provided);
					for (ModuleWire removedWire : entry.getValue()) {
						// invalidate the wire
						removedWire.invalidate();
					}

				}
				// remove any revisions that got removed as part of the refresh
				for (ModuleRevision removed : toRemoveRevisions) {
					removed.getRevisions().removeRevision(removed);
					moduleDatabase.removeCapabilities(removed);
				}
				// invalidate any removed wiring objects
				for (ModuleWiring moduleWiring : toRemoveWirings) {
					moduleWiring.invalidate();
				}
				moduleDatabase.setWiring(wiringCopy);
			} finally {
				moduleDatabase.writeUnlock();
			}
			// set the state of modules to unresolved
			for (Module module : modulesLocked) {
				if (State.RESOLVED.equals(module.getState())) {
					module.setState(State.INSTALLED);
					modulesUnresolved.add(module);
				}
			}
		} finally {
			for (Module module : modulesLocked) {
				module.unlockStateChange(ModuleEvent.UNRESOLVED);
			}
		}

		// publish unresolved events after giving up all locks
		for (Module module : modulesUnresolved) {
			adaptor.publishModuleEvent(ModuleEvent.UNRESOLVED, module, module);
		}
		return refreshTriggers;
	}

	private void checkSystemExtensionRefresh(Collection<Module> initial) {
		if (initial == null) {
			return;
		}
		Long zero = new Long(0);
		for (Iterator<Module> iModules = initial.iterator(); iModules.hasNext();) {
			Module m = iModules.next();
			if (m.getId().equals(zero)) {
				// never allow system bundle to be unresolved directly if the system module is active
				if (Module.ACTIVE_SET.contains(m.getState())) {
					iModules.remove();
				}
			} else {
				if (Module.RESOLVED_SET.contains(m.getState())) {
					// check if current revision is an extension of the system module
					ModuleRevision current = m.getCurrentRevision();
					if ((current.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) {
						ModuleWiring wiring = current.getWiring();
						if (wiring != null) {
							List<ModuleWire> hostWires = wiring.getRequiredModuleWires(HostNamespace.HOST_NAMESPACE);
							for (ModuleWire hostWire : hostWires) {
								if (hostWire.getProvider().getRevisions().getModule().getId().equals(zero)) {
									// The current revision is the extension to allow it to refresh
									// this would just shutdown the framework for no reason
									iModules.remove();
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Refreshes the specified collection of modules.
	 * @param initial the modules to refresh or {@code null} to refresh the
	 *     removal pending.
	 * @throws ResolutionException
	 * @see FrameworkWiring#refreshBundles(Collection, FrameworkListener...)
	 */
	public void refresh(Collection<Module> initial) throws ResolutionException {
		initial = initial == null ? null : new ArrayList<Module>(initial);
		Collection<Module> refreshTriggers = unresolve(initial);
		if (!isRefreshingSystemModule()) {
			resolve(refreshTriggers, false, true);
		}
	}

	/**
	 * Returns the dependency closure of for the specified modules.
	 * @param initial The initial modules for which to generate the dependency closure
	 * @return A collection containing a snapshot of the dependency closure of the specified 
	 *    modules, or an empty collection if there were no specified modules. 
	 */
	public Collection<Module> getDependencyClosure(Collection<Module> initial) {
		moduleDatabase.readLock();
		try {
			return getRefreshClosure(initial, moduleDatabase.getWiringsCopy());
		} finally {
			moduleDatabase.readUnlock();
		}
	}

	/**
	 * Returns the revisions that have {@link ModuleWiring#isCurrent() non-current}, {@link ModuleWiring#isInUse() in use} module wirings.
	 * @return A collection containing a snapshot of the revisions which have non-current, in use ModuleWirings,
	 * or an empty collection if there are no such revisions.
	 */
	public Collection<ModuleRevision> getRemovalPending() {
		return moduleDatabase.getRemovalPending();
	}

	/**
	 * Return the active start level value of this container.
	 * 
	 * If the container is in the process of changing the start level this
	 * method must return the active start level if this differs from the
	 * requested start level.
	 * 
	 * @return The active start level value of the Framework.
	 */
	public int getStartLevel() {
		return frameworkStartLevel.getStartLevel();
	}

	void setStartLevel(Module module, int startlevel) {
		frameworkStartLevel.setStartLevel(module, startlevel);
	}

	void open() {
		loadModules();
		frameworkStartLevel.open();
		frameworkWiring.open();
		synchronized (refreshingSystemModule) {
			refreshingSystemModule.clear();
		}
	}

	void close() {
		frameworkStartLevel.close();
		frameworkWiring.close();
		unloadModules();
	}

	private void loadModules() {
		List<Module> modules = null;
		moduleDatabase.readLock();
		try {
			modules = getModules();
			for (Module module : modules) {
				try {
					module.lockStateChange(ModuleEvent.RESOLVED);
					ModuleWiring wiring = moduleDatabase.getWiring(module.getCurrentRevision());
					if (wiring != null) {
						module.setState(State.RESOLVED);
					} else {
						module.setState(State.INSTALLED);
					}
				} catch (BundleException e) {
					throw new IllegalStateException("Unable to lock module state.", e); //$NON-NLS-1$
				}
			}
			Map<ModuleRevision, ModuleWiring> wirings = moduleDatabase.getWiringsCopy();
			for (ModuleWiring wiring : wirings.values()) {
				wiring.validate();
			}
		} finally {
			if (modules != null) {
				for (Module module : modules) {
					try {
						module.unlockStateChange(ModuleEvent.RESOLVED);
					} catch (IllegalMonitorStateException e) {
						// ignore
					}
				}
			}
			moduleDatabase.readUnlock();
		}
	}

	private void unloadModules() {
		List<Module> modules = null;
		moduleDatabase.readLock();
		try {
			modules = getModules();
			for (Module module : modules) {
				if (module.getId() != 0) {
					try {
						module.lockStateChange(ModuleEvent.UNINSTALLED);
					} catch (BundleException e) {
						throw new IllegalStateException("Unable to lock module state.", e); //$NON-NLS-1$
					}
					module.setState(State.UNINSTALLED);
				}
			}
			Map<ModuleRevision, ModuleWiring> wirings = moduleDatabase.getWiringsCopy();
			for (ModuleWiring wiring : wirings.values()) {
				wiring.invalidate();
			}
		} finally {
			if (modules != null) {
				for (Module module : modules) {
					if (module.getId() != 0) {
						try {
							module.unlockStateChange(ModuleEvent.UNINSTALLED);
						} catch (IllegalMonitorStateException e) {
							// ignore
						}
					}
				}
			}
			moduleDatabase.readUnlock();
		}
	}

	public void setInitialModuleStates() throws BundleException {
		moduleDatabase.readLock();
		try {
			List<Module> modules = getModules();
			for (Module module : modules) {
				if (module.getId() == 0) {
					module.lockStateChange(ModuleEvent.UNINSTALLED);
					try {
						module.setState(State.INSTALLED);
					} finally {
						module.unlockStateChange(ModuleEvent.UNINSTALLED);
					}
				} else {
					module.lockStateChange(ModuleEvent.UNINSTALLED);
					try {
						module.setState(State.UNINSTALLED);
					} finally {
						module.unlockStateChange(ModuleEvent.UNINSTALLED);
					}
				}
			}
			Map<ModuleRevision, ModuleWiring> wirings = moduleDatabase.getWiringsCopy();
			for (ModuleWiring wiring : wirings.values()) {
				wiring.invalidate();
			}
		} finally {
			moduleDatabase.readUnlock();
		}
	}

	Set<Module> getRefreshClosure(Collection<Module> initial, Map<ModuleRevision, ModuleWiring> wiringCopy) {
		Set<Module> refreshClosure = new HashSet<Module>();
		if (initial == null) {
			initial = new HashSet<Module>();
			Collection<ModuleRevision> removalPending = moduleDatabase.getRemovalPending();
			for (ModuleRevision revision : removalPending) {
				initial.add(revision.getRevisions().getModule());
			}
		}
		for (Module module : initial)
			addDependents(module, wiringCopy, refreshClosure);
		return refreshClosure;
	}

	private static void addDependents(Module module, Map<ModuleRevision, ModuleWiring> wiringCopy, Set<Module> refreshClosure) {
		if (refreshClosure.contains(module))
			return;
		refreshClosure.add(module);
		List<ModuleRevision> revisions = module.getRevisions().getModuleRevisions();
		for (ModuleRevision revision : revisions) {
			ModuleWiring wiring = wiringCopy.get(revision);
			if (wiring == null)
				continue;
			List<ModuleWire> provided = wiring.getProvidedModuleWires(null);
			// add all requirers of the provided wires
			for (ModuleWire providedWire : provided) {
				addDependents(providedWire.getRequirer().getRevisions().getModule(), wiringCopy, refreshClosure);
			}
			// add all hosts of a fragment
			if (revision.getTypes() == BundleRevision.TYPE_FRAGMENT) {
				List<ModuleWire> hosts = wiring.getRequiredModuleWires(HostNamespace.HOST_NAMESPACE);
				for (ModuleWire hostWire : hosts) {
					addDependents(hostWire.getProvider().getRevisions().getModule(), wiringCopy, refreshClosure);
				}
			}
		}
	}

	static Collection<ModuleRevision> getDependencyClosure(ModuleRevision initial, Map<ModuleRevision, ModuleWiring> wiringCopy) {
		Set<ModuleRevision> dependencyClosure = new HashSet<ModuleRevision>();
		addDependents(initial, wiringCopy, dependencyClosure);
		return dependencyClosure;
	}

	private static void addDependents(ModuleRevision revision, Map<ModuleRevision, ModuleWiring> wiringCopy, Set<ModuleRevision> dependencyClosure) {
		if (dependencyClosure.contains(revision))
			return;
		dependencyClosure.add(revision);
		ModuleWiring wiring = wiringCopy.get(revision);
		if (wiring == null)
			return;
		List<ModuleWire> provided = wiring.getProvidedModuleWires(null);
		// add all requirers of the provided wires
		for (ModuleWire providedWire : provided) {
			addDependents(providedWire.getRequirer(), wiringCopy, dependencyClosure);
		}
		// add all hosts of a fragment
		if (revision.getTypes() == BundleRevision.TYPE_FRAGMENT) {
			List<ModuleWire> hosts = wiring.getRequiredModuleWires(HostNamespace.HOST_NAMESPACE);
			for (ModuleWire hostWire : hosts) {
				addDependents(hostWire.getProvider(), wiringCopy, dependencyClosure);
			}
		}
	}

	Bundle getSystemBundle() {
		Module systemModule = moduleDatabase.getModule(0);
		return systemModule == null ? null : systemModule.getBundle();
	}

	void checkAdminPermission(Bundle bundle, String action) {
		if (bundle == null)
			return;
		SecurityManager sm = System.getSecurityManager();
		if (sm != null)
			sm.checkPermission(new AdminPermission(bundle, action));
	}

	void refreshSystemModule() {
		final SystemModule systemModule = (SystemModule) moduleDatabase.getModule(0);
		synchronized (refreshingSystemModule) {
			if (refreshingSystemModule.contains(systemModule)) {
				return;
			}
			refreshingSystemModule.add(systemModule);
			getAdaptor().refreshedSystemModule();
		}
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					systemModule.lockStateChange(ModuleEvent.UNRESOLVED);
					try {
						systemModule.stop();
					} finally {
						systemModule.unlockStateChange(ModuleEvent.UNRESOLVED);
					}
				} catch (BundleException e) {
					e.printStackTrace();
				}
			}
		});
		t.start();
	}

	boolean isRefreshingSystemModule() {
		synchronized (refreshingSystemModule) {
			return !refreshingSystemModule.isEmpty();
		}
	}

	class ContainerWiring implements FrameworkWiring, EventDispatcher<ContainerWiring, FrameworkListener[], Collection<Module>> {
		private final Object monitor = new Object();
		private EventManager refreshThread = null;

		@Override
		public Bundle getBundle() {
			return getSystemBundle();
		}

		@Override
		public void refreshBundles(Collection<Bundle> bundles, FrameworkListener... listeners) {
			checkAdminPermission(getBundle(), AdminPermission.RESOLVE);
			Collection<Module> modules = getModules(bundles);

			// queue to refresh in the background
			// notice that we only do one refresh operation at a time
			CopyOnWriteIdentityMap<ContainerWiring, FrameworkListener[]> dispatchListeners = new CopyOnWriteIdentityMap<ModuleContainer.ContainerWiring, FrameworkListener[]>();
			dispatchListeners.put(this, listeners);
			ListenerQueue<ContainerWiring, FrameworkListener[], Collection<Module>> queue = new ListenerQueue<ModuleContainer.ContainerWiring, FrameworkListener[], Collection<Module>>(getManager());
			queue.queueListeners(dispatchListeners.entrySet(), this);

			// dispatch the refresh job
			queue.dispatchEventAsynchronous(0, modules);
		}

		@Override
		public boolean resolveBundles(Collection<Bundle> bundles) {
			checkAdminPermission(getBundle(), AdminPermission.RESOLVE);
			Collection<Module> modules = getModules(bundles);
			try {
				resolve(modules, false);
			} catch (ResolutionException e) {
				return false;
			}
			if (modules == null) {
				modules = ModuleContainer.this.getModules();
			}
			for (Module module : modules) {
				if (getWiring(module.getCurrentRevision()) == null)
					return false;
			}
			return true;
		}

		@Override
		public Collection<Bundle> getRemovalPendingBundles() {
			moduleDatabase.readLock();
			try {
				Collection<Bundle> removalPendingBundles = new HashSet<Bundle>();
				Collection<ModuleRevision> removalPending = moduleDatabase.getRemovalPending();
				for (ModuleRevision moduleRevision : removalPending) {
					removalPendingBundles.add(moduleRevision.getBundle());
				}
				return removalPendingBundles;
			} finally {
				moduleDatabase.readUnlock();
			}
		}

		@Override
		public Collection<Bundle> getDependencyClosure(Collection<Bundle> bundles) {
			Collection<Module> modules = getModules(bundles);
			moduleDatabase.readLock();
			try {
				Collection<Module> closure = getRefreshClosure(modules, moduleDatabase.getWiringsCopy());
				Collection<Bundle> result = new ArrayList<Bundle>(closure.size());
				for (Module module : closure) {
					result.add(module.getBundle());
				}
				return result;
			} finally {
				moduleDatabase.readUnlock();
			}
		}

		private Collection<Module> getModules(final Collection<Bundle> bundles) {
			if (bundles == null)
				return null;
			return AccessController.doPrivileged(new PrivilegedAction<Collection<Module>>() {
				@Override
				public Collection<Module> run() {
					Collection<Module> result = new ArrayList<Module>(bundles.size());
					for (Bundle bundle : bundles) {
						Module module = bundle.adapt(Module.class);
						if (module == null)
							throw new IllegalStateException("Could not adapt a bundle to a module."); //$NON-NLS-1$
						result.add(module);
					}
					return result;
				}
			});
		}

		@Override
		public void dispatchEvent(ContainerWiring eventListener, FrameworkListener[] frameworkListeners, int eventAction, Collection<Module> eventObject) {
			try {
				refresh(eventObject);
			} catch (ResolutionException e) {
				adaptor.publishContainerEvent(ContainerEvent.ERROR, moduleDatabase.getModule(0), e);
			} finally {
				adaptor.publishContainerEvent(ContainerEvent.REFRESH, moduleDatabase.getModule(0), null, frameworkListeners);
			}
		}

		private EventManager getManager() {
			synchronized (monitor) {
				if (refreshThread == null) {
					refreshThread = new EventManager("Refresh Thread: " + adaptor.toString()); //$NON-NLS-1$
				}
				return refreshThread;
			}
		}

		// because of bug 378491 we have to synchronize access to the manager
		// so we can close and re-open ourselves
		void close() {
			synchronized (monitor) {
				// force a manager to be created if it did not exist
				EventManager manager = getManager();
				// this prevents any operations until open is called
				manager.close();
			}
		}

		void open() {
			synchronized (monitor) {
				if (refreshThread != null) {
					// Make sure it is closed just incase
					refreshThread.close();
					// a new one will be constructed on demand
					refreshThread = null;
				}
			}
		}
	}

	class ContainerStartLevel implements FrameworkStartLevel, EventDispatcher<Module, FrameworkListener[], Integer> {
		static final int USE_BEGINNING_START_LEVEL = Integer.MIN_VALUE;
		private static final int FRAMEWORK_STARTLEVEL = 1;
		private static final int MODULE_STARTLEVEL = 2;
		private final AtomicInteger activeStartLevel = new AtomicInteger(0);
		private final Object eventManagerLock = new Object();
		private EventManager startLevelThread = null;
		private final Object frameworkStartLevelLock = new Object();

		@Override
		public Bundle getBundle() {
			return getSystemBundle();
		}

		@Override
		public int getStartLevel() {
			return activeStartLevel.get();
		}

		void setStartLevel(Module module, int startlevel) {
			checkAdminPermission(module.getBundle(), AdminPermission.EXECUTE);
			if (module.getId() == 0) {
				throw new IllegalArgumentException("Cannot set the start level of the system bundle.");
			}
			if (startlevel < 1) {
				throw new IllegalArgumentException("Cannot set the start level to less than 1: " + startlevel);
			}
			if (module.getStartLevel() == startlevel) {
				return; // do nothing
			}
			moduleDatabase.setStartLevel(module, startlevel);
			// queue start level operation in the background
			// notice that we only do one start level operation at a time
			CopyOnWriteIdentityMap<Module, FrameworkListener[]> dispatchListeners = new CopyOnWriteIdentityMap<Module, FrameworkListener[]>();
			dispatchListeners.put(module, new FrameworkListener[0]);
			ListenerQueue<Module, FrameworkListener[], Integer> queue = new ListenerQueue<Module, FrameworkListener[], Integer>(getManager());
			queue.queueListeners(dispatchListeners.entrySet(), this);

			// dispatch the start level job
			queue.dispatchEventAsynchronous(MODULE_STARTLEVEL, startlevel);
		}

		@Override
		public void setStartLevel(int startlevel, FrameworkListener... listeners) {
			checkAdminPermission(getBundle(), AdminPermission.STARTLEVEL);
			if (startlevel < 1) {
				throw new IllegalArgumentException("Cannot set the start level to less than 1: " + startlevel);
			}

			if (activeStartLevel.get() == 0) {
				throw new IllegalStateException("The system has not be activated yet.");
			}
			// queue start level operation in the background
			// notice that we only do one start level operation at a time
			CopyOnWriteIdentityMap<Module, FrameworkListener[]> dispatchListeners = new CopyOnWriteIdentityMap<Module, FrameworkListener[]>();
			dispatchListeners.put(moduleDatabase.getModule(0), listeners);
			ListenerQueue<Module, FrameworkListener[], Integer> queue = new ListenerQueue<Module, FrameworkListener[], Integer>(getManager());
			queue.queueListeners(dispatchListeners.entrySet(), this);

			// dispatch the start level job
			queue.dispatchEventAsynchronous(FRAMEWORK_STARTLEVEL, startlevel);
		}

		@Override
		public int getInitialBundleStartLevel() {
			return moduleDatabase.getInitialModuleStartLevel();
		}

		@Override
		public void setInitialBundleStartLevel(int startlevel) {
			checkAdminPermission(getBundle(), AdminPermission.STARTLEVEL);
			moduleDatabase.setInitialModuleStartLevel(startlevel);
		}

		@Override
		public void dispatchEvent(Module module, FrameworkListener[] listeners, int eventAction, Integer startlevel) {
			switch (eventAction) {
				case FRAMEWORK_STARTLEVEL :
					doContainerStartLevel(module, startlevel, listeners);
					break;
				case MODULE_STARTLEVEL :
					try {
						if (getStartLevel() < startlevel) {
							if (Module.ACTIVE_SET.contains(module.getState())) {
								// Note that we don't need to hold the state change lock
								// here when checking the active status because no other
								// thread will successfully be able to start this bundle
								// since the start-level is no longer met.
								module.stop(StopOptions.TRANSIENT);
							}
						} else {
							module.start(StartOptions.TRANSIENT_IF_AUTO_START, StartOptions.TRANSIENT_RESUME);
						}
					} catch (BundleException e) {
						adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e);
					}
					break;
				default :
					break;
			}
		}

		void doContainerStartLevel(Module module, int newStartLevel, FrameworkListener... listeners) {
			synchronized (frameworkStartLevelLock) {
				if (newStartLevel == USE_BEGINNING_START_LEVEL) {
					String beginningSL = adaptor.getProperty(Constants.FRAMEWORK_BEGINNING_STARTLEVEL);
					newStartLevel = beginningSL == null ? 1 : Integer.parseInt(beginningSL);
				}
				try {
					int currentSL = getStartLevel();
					// Note that we must get a new list of modules each time;
					// this is because additional modules could have been installed from the previous start-level
					if (newStartLevel > currentSL) {
						for (int i = currentSL; i < newStartLevel; i++) {
							int toStartLevel = i + 1;
							activeStartLevel.set(toStartLevel);
							incStartLevel(toStartLevel, moduleDatabase.getSortedModules(Sort.BY_START_LEVEL));
						}
					} else {
						for (int i = currentSL; i > newStartLevel; i--) {
							int toStartLevel = i - 1;
							activeStartLevel.set(toStartLevel);
							decStartLevel(toStartLevel, moduleDatabase.getSortedModules(Sort.BY_START_LEVEL, Sort.BY_DEPENDENCY));
						}
					}
					adaptor.publishContainerEvent(ContainerEvent.START_LEVEL, module, null, listeners);
				} catch (Error e) {
					adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e, listeners);
					throw e;
				} catch (RuntimeException e) {
					adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e, listeners);
					throw e;
				}
			}
		}

		private void incStartLevel(int toStartLevel, List<Module> sortedModules) {
			incStartLevel(toStartLevel, sortedModules, true);
			incStartLevel(toStartLevel, sortedModules, false);
		}

		private void incStartLevel(int toStartLevel, List<Module> sortedModules, boolean lazyOnly) {
			for (Module module : sortedModules) {
				if (isRefreshingSystemModule()) {
					return;
				}
				try {
					int moduleStartLevel = module.getStartLevel();
					if (moduleStartLevel < toStartLevel) {
						// skip modules who should have already been started
						continue;
					} else if (moduleStartLevel == toStartLevel) {
						boolean isLazyStart = module.isLazyActivate();
						if (lazyOnly ? isLazyStart : !isLazyStart) {
							try {
								module.start(StartOptions.TRANSIENT_IF_AUTO_START, StartOptions.TRANSIENT_RESUME);
							} catch (BundleException e) {
								adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e);
							} catch (IllegalStateException e) {
								// been uninstalled
								continue;
							}
						}
					} else {
						// can stop resuming since any remaining modules have a greater startlevel than the active startlevel
						break;
					}
				} catch (IllegalStateException e) {
					// been uninstalled
					continue;
				}
			}
		}

		private void decStartLevel(int toStartLevel, List<Module> sortedModules) {
			ListIterator<Module> iModules = sortedModules.listIterator(sortedModules.size());
			while (iModules.hasPrevious()) {
				Module module = iModules.previous();
				try {
					int moduleStartLevel = module.getStartLevel();
					if (moduleStartLevel > toStartLevel + 1) {
						// skip modules who should have already been stopped
						continue;
					} else if (moduleStartLevel <= toStartLevel) {
						// stopped all modules we are going to for this start level
						break;
					}
					try {
						if (Module.ACTIVE_SET.contains(module.getState())) {
							// Note that we don't need to hold the state change lock
							// here when checking the active status because no other
							// thread will successfully be able to start this bundle
							// since the start-level is no longer met.
							module.stop(StopOptions.TRANSIENT);
						}
					} catch (BundleException e) {
						adaptor.publishContainerEvent(ContainerEvent.ERROR, module, e);
					}
				} catch (IllegalStateException e) {
					// been uninstalled
					continue;
				}
			}
		}

		private EventManager getManager() {
			synchronized (eventManagerLock) {
				if (startLevelThread == null) {
					startLevelThread = new EventManager("Start Level: " + adaptor.toString()); //$NON-NLS-1$
				}
				return startLevelThread;
			}
		}

		// because of bug 378491 we have to synchronize access to the manager
		// so we can close and re-open ourselves
		void close() {
			synchronized (eventManagerLock) {
				// force a manager to be created if it did not exist
				EventManager manager = getManager();
				// this prevents any operations until open is called
				manager.close();
			}
		}

		void open() {
			synchronized (eventManagerLock) {
				if (startLevelThread != null) {
					// Make sure it is closed just incase
					startLevelThread.close();
					// a new one will be constructed on demand
					startLevelThread = null;
				}
			}
		}
	}
}