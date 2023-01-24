/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.rest.directory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.GuacamoleUnsupportedException;
import org.apache.guacamole.net.auth.AtomicDirectoryOperation;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.net.auth.Directory;
import org.apache.guacamole.net.auth.Identifiable;
import org.apache.guacamole.net.auth.Permissions;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.net.auth.permission.ObjectPermission;
import org.apache.guacamole.net.auth.permission.ObjectPermissionSet;
import org.apache.guacamole.net.auth.permission.SystemPermission;
import org.apache.guacamole.net.auth.permission.SystemPermissionSet;
import org.apache.guacamole.net.event.DirectoryEvent;
import org.apache.guacamole.net.event.DirectoryFailureEvent;
import org.apache.guacamole.net.event.DirectorySuccessEvent;
import org.apache.guacamole.rest.event.ListenerService;
import org.apache.guacamole.rest.jsonpatch.APIPatch;
import org.apache.guacamole.rest.jsonpatch.APIPatchError;
import org.apache.guacamole.rest.jsonpatch.APIPatchFailureException;
import org.apache.guacamole.rest.jsonpatch.APIPatchOutcome;
import org.apache.guacamole.rest.jsonpatch.APIPatchResponse;

/**
 * A REST resource which abstracts the operations available on all Guacamole
 * Directory implementations, such as the creation of new objects, or listing
 * of existing objects. A DirectoryResource functions as the parent of any
 * number of child DirectoryObjectResources, which are created with the factory
 * provided at the time of this object's construction.
 *
 * @param <InternalType>
 *     The type of object contained within the Directory that this
 *     DirectoryResource exposes. To avoid coupling the REST API too tightly to
 *     the extension API, these objects are not directly serialized or
 *     deserialized when handling REST requests.
 *
 * @param <ExternalType>
 *     The type of object used in interchange (ie: serialized/deserialized as
 *     JSON) between REST clients and this DirectoryResource when representing
 *     the InternalType.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public abstract class DirectoryResource<InternalType extends Identifiable, ExternalType> {

    /**
     * The user that is accessing this resource.
     */
    private final AuthenticatedUser authenticatedUser;
    
    /**
     * The UserContext associated with the Directory being exposed by this
     * DirectoryResource.
     */
    private final UserContext userContext;

    /**
     * The type of object contained within the Directory exposed by this
     * DirectoryResource.
     */
    private final Class<InternalType> internalType;

    /**
     * The Directory being exposed by this DirectoryResource.
     */
    private final Directory<InternalType> directory;

    /**
     * A DirectoryObjectTranslator implementation which handles the type of
     * objects contained within the Directory exposed by this DirectoryResource.
     */
    private final DirectoryObjectTranslator<InternalType, ExternalType> translator;

    /**
     * A factory which can be used to create instances of resources representing
     * individual objects contained within the Directory exposed by this
     * DirectoryResource.
     */
    private final DirectoryObjectResourceFactory<InternalType, ExternalType> resourceFactory;

    /**
     * Service for dispatching events to registered listeners.
     */
    @Inject
    private ListenerService listenerService;

    /**
     * Creates a new DirectoryResource which exposes the operations available
     * for the given Directory.
     *
     * @param authenticatedUser
     *     The user that is accessing this resource.
     *
     * @param userContext
     *     The UserContext associated with the given Directory.
     *
     * @param internalType
     *     The type of object contained within the given Directory.
     *
     * @param directory
     *     The Directory being exposed by this DirectoryResource.
     *
     * @param translator
     *     A DirectoryObjectTranslator implementation which handles the type of
     *     objects contained within the given Directory.
     *
     * @param resourceFactory
     *     A factory which can be used to create instances of resources
     *     representing individual objects contained within the given Directory.
     */
    public DirectoryResource(AuthenticatedUser authenticatedUser,
            UserContext userContext, Class<InternalType> internalType,
            Directory<InternalType> directory,
            DirectoryObjectTranslator<InternalType, ExternalType> translator,
            DirectoryObjectResourceFactory<InternalType, ExternalType> resourceFactory) {
        this.authenticatedUser = authenticatedUser;
        this.userContext = userContext;
        this.directory = directory;
        this.internalType = internalType;
        this.translator = translator;
        this.resourceFactory = resourceFactory;
    }

    /**
     * Returns the ObjectPermissionSet defined within the given Permissions
     * that represents the permissions affecting objects available within this
     * DirectoryResource.
     *
     * @param permissions
     *     The Permissions object from which the ObjectPermissionSet should be
     *     retrieved.
     *
     * @return
     *     The ObjectPermissionSet defined within the given Permissions object
     *     that represents the permissions affecting objects available within
     *     this DirectoryResource.
     *
     * @throws GuacamoleException
     *     If an error prevents retrieval of permissions.
     */
    protected abstract ObjectPermissionSet getObjectPermissions(
            Permissions permissions) throws GuacamoleException;

    /**
     * Notifies all registered listeners that the given operation has succeeded
     * against the object having the given identifier within the Directory
     * represented by this resource.
     * 
     * @param operation
     *     The operation that was performed.
     *
     * @param identifier
     *     The identifier of the object affected by the operation.
     *
     * @param object
     *     The specific object affected by the operation, if available. If not
     *     available, this may be null.
     *
     * @throws GuacamoleException
     *     If a listener throws a GuacamoleException from its event handler.
     */
    protected void fireDirectorySuccessEvent(DirectoryEvent.Operation operation,
                String identifier, InternalType object)
            throws GuacamoleException {
        listenerService.handleEvent(new DirectorySuccessEvent<InternalType>() {

            @Override
            public Directory.Type getDirectoryType() {
                return Directory.Type.of(internalType);
            }

            @Override
            public DirectoryEvent.Operation getOperation() {
                return operation;
            }

            @Override
            public String getObjectIdentifier() {
                return identifier;
            }

            @Override
            public InternalType getObject() {
                return object;
            }

            @Override
            public AuthenticatedUser getAuthenticatedUser() {
                return authenticatedUser;
            }

            @Override
            public AuthenticationProvider getAuthenticationProvider() {
                return userContext.getAuthenticationProvider();
            }

        });
    }

    /**
     * Notifies all registered listeners that the given operation has failed
     * against the object having the given identifier within the Directory
     * represented by this resource.
     *
     * @param operation
     *     The operation that failed.
     *
     * @param identifier
     *     The identifier of the object that would have been affected by the
     *     operation had it succeeded.
     *
     * @param object
     *     The specific object would have been affected by the operation, if
     *     available, had it succeeded, including any changes that were
     *     intended to be applied to the object. If not available, this may be
     *     null.
     *
     * @param failure
     *     The failure that occurred.
     *
     * @throws GuacamoleException
     *     If a listener throws a GuacamoleException from its event handler.
     */
    protected void fireDirectoryFailureEvent(DirectoryEvent.Operation operation,
                String identifier, InternalType object, Throwable failure)
            throws GuacamoleException {
        listenerService.handleEvent(new DirectoryFailureEvent<InternalType>() {

            @Override
            public Directory.Type getDirectoryType() {
                return Directory.Type.of(internalType);
            }

            @Override
            public DirectoryEvent.Operation getOperation() {
                return operation;
            }

            @Override
            public String getObjectIdentifier() {
                return identifier;
            }

            @Override
            public InternalType getObject() {
                return object;
            }

            @Override
            public AuthenticatedUser getAuthenticatedUser() {
                return authenticatedUser;
            }

            @Override
            public AuthenticationProvider getAuthenticationProvider() {
                return userContext.getAuthenticationProvider();
            }

            @Override
            public Throwable getFailure() {
                return failure;
            }

        });
    }

    /**
     * Returns the user accessing this resource.
     *
     * @return
     *     The user accessing this resource.
     */
    protected AuthenticatedUser getAuthenticatedUser() {
        return authenticatedUser;
    }

    /**
     * Returns the UserContext providing the Directory exposed by this
     * resource.
     *
     * @return 
     *     The UserContext providing the Directory exposed by this resource.
     */
    protected UserContext getUserContext() {
        return userContext;
    }

    /**
     * Returns the Directory exposed by this resource.
     *
     * @return 
     *     The Directory exposed by this resource.
     */
    protected Directory<InternalType> getDirectory() {
        return directory;
    }

    /**
     * Returns a factory that can be used to create instances of resources
     * representing individual objects contained within the Directory exposed
     * by this resource.
     *
     * @return 
     *     A factory that can be used to create instances of resources
     *     representing individual objects contained within the Directory
     *     exposed by this resource.
     */
    public DirectoryObjectResourceFactory<InternalType, ExternalType> getResourceFactory() {
        return resourceFactory;
    }

    /**
     * Filter and sanitize the provided external object, translate to the
     * internal type, and return the translated internal object.
     *
     * @param object
     *     The external object to filter and translate.
     *
     * @return
     *     The filtered and translated internal object.
     *
     * @throws GuacamoleException
     *     If an error occurs while filtering or translating the external
     *     object.
     */
    private InternalType filterAndTranslate(ExternalType object)
            throws GuacamoleException {

        // Filter and sanitize the external object
        translator.filterExternalObject(userContext, object);

        // Translate to the internal type
        return translator.toInternalObject(object);
    }

    /**
     * Returns a map of all objects available within this DirectoryResource,
     * filtering the returned map by the given permission, if specified.
     *
     * @param permissions
     *     The set of permissions to filter with. A user must have one or more
     *     of these permissions for the affected objects to appear in the
     *     result. If null, no filtering will be performed.
     *
     * @return
     *     A map of all visible objects. If a permission was specified, this
     *     map will contain only those objects for which the current user has
     *     that permission.
     *
     * @throws GuacamoleException
     *     If an error is encountered while retrieving the objects.
     */
    @GET
    public Map<String, ExternalType> getObjects(
            @QueryParam("permission") List<ObjectPermission.Type> permissions)
            throws GuacamoleException {

        // An admin user has access to all objects
        Permissions effective = userContext.self().getEffectivePermissions();
        SystemPermissionSet systemPermissions = effective.getSystemPermissions();
        boolean isAdmin = systemPermissions.hasPermission(SystemPermission.Type.ADMINISTER);

        // Filter objects, if requested
        Collection<String> identifiers = directory.getIdentifiers();
        if (!isAdmin && permissions != null && !permissions.isEmpty()) {
            ObjectPermissionSet objectPermissions = getObjectPermissions(effective);
            identifiers = objectPermissions.getAccessibleObjects(permissions, identifiers);
        }

        // Translate each retrieved object into the corresponding external object
        Map<String, ExternalType> apiObjects = new HashMap<String, ExternalType>();
        for (InternalType object : directory.getAll(identifiers))
            apiObjects.put(object.getIdentifier(), translator.toExternalObject(object));

        return apiObjects;

    }

    /**
     * Applies the given object patches, updating the underlying directory
     * accordingly. This operation supports addition, update, and removal of
     * objects through the "add", "replace", and "remove" patch operation.
     * The path of each patch operation is of the form "/ID" where ID is the
     * identifier of the object being modified. In the case of object creation,
     * the identifier is ignored, as the identifier will be automatically
     * provided. This operation is atomic.
     *
     * @param patches
     *     The patches to apply for this request.
     *
     * @throws GuacamoleException
     *     If an error occurs while adding, updating, or removing objects.
     *
     * @return
     *     A response describing the outcome of each patch. Only the identifier
     *     of each patched object will be included in the response, not the
     *     full object.
     */
    @PATCH
    public APIPatchResponse patchObjects(List<APIPatch<ExternalType>> patches)
            throws GuacamoleException {

        // An outcome for each patch included in the request. This list
        // may include both success and failure responses, though the
        // presense of any failure would indicated that the entire
        // request has failed and no changes have been made.
        List<APIPatchOutcome> patchOutcomes = new ArrayList<>();

        // Perform all requested operations atomically
        directory.tryAtomically(new AtomicDirectoryOperation<InternalType>() {

            @Override
            public void executeOperation(boolean atomic, Directory<InternalType> directory)
                    throws GuacamoleException {

                // If the underlying directory implentation does not support
                // atomic operations, abort the patch operation. This REST
                // endpoint requires that operations be performed atomically.
                if (!atomic)
                    throw new GuacamoleUnsupportedException(
                            "Atomic operations are not supported. " +
                            "The patch cannot be executed.");

                // Keep a list of all objects that have been successfully
                // added, updated, or removed
                Collection<InternalType> addedObjects = new ArrayList<>();
                Collection<InternalType> updatedObjects = new ArrayList<>();
                Collection<String> removedIdentifiers = new ArrayList<>();

                // A list of all responses associated with the successful
                // creation of new objects
                List<APIPatchOutcome> creationSuccesses = new ArrayList<>();

                // True if any operation in the patch failed. Any failure will
                // fail the request, though won't result in immediate stoppage
                // since more errors may yet be uncovered.
                boolean failed = false;

                // Apply each operation specified within the patch
                for (APIPatch<ExternalType> patch : patches) {

                    // Retrieve and validate path
                    String path = patch.getPath();
                    if (!path.startsWith("/"))
                        throw new GuacamoleClientException("Patch paths must start with \"/\".");

                    if(patch.getOp() == APIPatch.Operation.add) {

                        // Filter/sanitize object contents
                        InternalType internal = filterAndTranslate(patch.getValue());

                        try {

                            // Attempt to add the new object
                            directory.add(internal);

                            // Add the object to the list if addition was successful
                            addedObjects.add(internal);

                            // Add a success outcome describing the object creation
                            APIPatchOutcome response = new APIPatchOutcome(
                                    patch.getOp(), internal.getIdentifier(), path);
                            patchOutcomes.add(response);
                            creationSuccesses.add(response);

                        }

                        catch (GuacamoleException | RuntimeException | Error e) {
                            failed = true;
                            fireDirectoryFailureEvent(
                                    DirectoryEvent.Operation.ADD,
                                    internal.getIdentifier(), internal, e);

                            /*
                             * If the failure represents an understood issue,
                             * create a failure outcome for this failed patch.
                             */
                            if (e instanceof GuacamoleException)
                                patchOutcomes.add(new APIPatchError(
                                        patch.getOp(), null, path,
                                        ((GuacamoleException) e).getMessage()));

                            // If an unexpected failure occurs, fall through to the
                            // standard API error handling
                            else
                                throw e;
                        }

                    }

                    else if (patch.getOp() == APIPatch.Operation.replace) {

                        // Filter/sanitize object contents
                        InternalType internal = filterAndTranslate(patch.getValue());

                        try {

                            // Set the model to the supplied identifier from the PATH
                            String identifier = path.substring(1);

                            if (identifier == null)
                                throw new GuacamoleClientException(
                                        "An identifier is required when updating.");

                            // If there's an identiifer provided in the value,
                            // make sure it matches the one from the path
                            String valueId = internal.getIdentifier();
                            if (valueId != null && !valueId.equals(identifier))
                                throw new GuacamoleClientException(
                                        "Identifier mismatch between path and value.");

                            internal.setIdentifier(identifier);

                            // Attempt to update the object
                            directory.update(internal);

                            // Add the object to the list if the update was successful
                            updatedObjects.add(internal);

                            // Add a success outcome describing the object update
                            APIPatchOutcome response = new APIPatchOutcome(
                                    patch.getOp(), internal.getIdentifier(), path);
                            patchOutcomes.add(response);
                            creationSuccesses.add(response);
                        }

                        catch (GuacamoleException | RuntimeException | Error e) {
                            failed = true;
                            fireDirectoryFailureEvent(
                                    DirectoryEvent.Operation.UPDATE,
                                    internal.getIdentifier(), internal, e);

                            /*
                             * If the failure represents an understood issue,
                             * create a failure outcome for this failed patch.
                             */
                            if (e instanceof GuacamoleException)
                                patchOutcomes.add(new APIPatchError(
                                        patch.getOp(), internal.getIdentifier(), path,
                                        ((GuacamoleException) e).getMessage()));

                            // If an unexpected failure occurs, fall through to the
                            // standard API error handling
                            else
                                throw e;
                        }
                    }

                    // Append each identifier to the list, to be removed atomically
                    else if (patch.getOp() == APIPatch.Operation.remove) {

                        String identifier = path.substring(1);

                        try {

                            // Attempt to remove the object
                            directory.remove(identifier);

                            // Add the object to the list if the removal was successful
                            removedIdentifiers.add(identifier);

                            // Add a success outcome describing the object removal
                            APIPatchOutcome response = new APIPatchOutcome(
                                    patch.getOp(), identifier, path);
                            patchOutcomes.add(response);
                            creationSuccesses.add(response);
                        }
                        catch (GuacamoleException | RuntimeException | Error e) {
                            failed = true;
                            fireDirectoryFailureEvent(
                                    DirectoryEvent.Operation.REMOVE,
                                    identifier, null, e);

                            /*
                             * If the failure represents an understood issue,
                             * create a failure outcome for this failed patch.
                             */
                            if (e instanceof GuacamoleException)
                                patchOutcomes.add(new APIPatchError(
                                        patch.getOp(), identifier, path,
                                        ((GuacamoleException) e).getMessage()));

                            // If an unexpected failure occurs, fall through to the
                            // standard API error handling
                            else
                                throw e;
                        }
                    }

                }

                // If any operation failed
                if (failed) {

                    // Any identifiers for objects created during this request
                    // will no longer be valid, since the creation of those
                    // objects will be rolled back.
                    creationSuccesses.forEach(
                            response -> response.clearIdentifier());

                    // Return an error response, including any failures that
                    // caused the failure of any patch in the request
                    throw new APIPatchFailureException(
                            "The provided patches failed to apply.", patchOutcomes);

                }

                // Fire directory success events for each created object
                Iterator<InternalType> addedIterator = addedObjects.iterator();
                while (addedIterator.hasNext()) {

                    InternalType internal = addedIterator.next();
                    fireDirectorySuccessEvent(
                            DirectoryEvent.Operation.ADD,
                            internal.getIdentifier(), internal);

                }

                // Fire directory success events for each updated object
                Iterator<InternalType> updatedIterator = updatedObjects.iterator();
                while (updatedIterator.hasNext()) {

                    InternalType internal = updatedIterator.next();
                    fireDirectorySuccessEvent(
                            DirectoryEvent.Operation.UPDATE,
                            internal.getIdentifier(), internal);

                }

                // Fire directory success events for each removed object
                Iterator<String> removedIterator = removedIdentifiers.iterator();
                while (removedIterator.hasNext()) {

                    String identifier = removedIterator.next();
                    fireDirectorySuccessEvent(
                            DirectoryEvent.Operation.UPDATE,
                            identifier, null);

                }

            }

        });

        // Return a list of outcomes, one for each patch in the request
        return new APIPatchResponse(patchOutcomes);

    }

    /**
     * Creates a new object within the underlying Directory, returning the
     * object that was created. The identifier of the created object will be
     * populated, if applicable.
     *
     * @param object
     *     The object to create.
     *
     * @return
     *     The object that was created.
     *
     * @throws GuacamoleException
     *     If an error occurs while adding the object to the underlying
     *     directory.
     */
    @POST
    public ExternalType createObject(ExternalType object)
            throws GuacamoleException {

        // Validate that data was provided
        if (object == null)
            throw new GuacamoleClientException("Data must be submitted when creating objects.");

        // Filter/sanitize object contents
        InternalType internal = filterAndTranslate(object);

        // Create the new object within the directory
        try {
            directory.add(internal);
            fireDirectorySuccessEvent(DirectoryEvent.Operation.ADD, internal.getIdentifier(), internal);
            return object;
        }
        catch (GuacamoleException | RuntimeException | Error e) {
            fireDirectoryFailureEvent(DirectoryEvent.Operation.ADD, internal.getIdentifier(), internal, e);
            throw e;
        }

    }

    /**
     * Retrieves an individual object, returning a DirectoryObjectResource
     * implementation which exposes operations available on that object.
     *
     * @param identifier
     *     The identifier of the object to retrieve.
     *
     * @return
     *     A DirectoryObjectResource exposing operations available on the
     *     object having the given identifier.
     *
     * @throws GuacamoleException
     *     If an error occurs while retrieving the object.
     */
    @Path("{identifier}")
    public DirectoryObjectResource<InternalType, ExternalType>
        getObjectResource(@PathParam("identifier") String identifier)
            throws GuacamoleException {

        // Retrieve the object having the given identifier
        InternalType object;
        try {
            object = directory.get(identifier);
            if (object == null)
                throw new GuacamoleResourceNotFoundException("Not found: \"" + identifier + "\"");
        }
        catch (GuacamoleException | RuntimeException | Error e) {
            fireDirectoryFailureEvent(DirectoryEvent.Operation.GET, identifier, null, e);
            throw e;
        }

        // Return a resource which provides access to the retrieved object
        DirectoryObjectResource<InternalType, ExternalType> resource = resourceFactory.create(authenticatedUser, userContext, directory, object);
        fireDirectorySuccessEvent(DirectoryEvent.Operation.GET, identifier, object);
        return resource;

    }

}
