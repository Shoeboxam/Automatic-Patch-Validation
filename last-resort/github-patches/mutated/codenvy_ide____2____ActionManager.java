/*
 * Copyright (C) 2013 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.codenvy.ide.api.ui.action;

import com.codenvy.ide.api.extension.SDK;

/**
 * A manager for actions. Used to register and unregister actions, also
 * contains utility methods to easily fetch action by id and id by action.
 *
 * @author <a href="mailto:evidolob@codenvy.com">Evgen Vidolob</a>
 * @version $Id:
 * @see Action
 */
@SDK(title = "ide.api.ui.action")
public interface ActionManager {

    /**
     * Returns action associated with the specified actionId.
     *
     * @param actionId
     *         Id of the registered action
     * @return Action associated with the specified actionId, <code>null</code> if
     *         there is no actions associated with the speicified actionId
     * @throws java.lang.IllegalArgumentException
     *         if <code>actionId</code> is <code>null</code>
     */
    public Action getAction(String actionId);

    /**
     * Returns actionId associated with the specified action.
     *
     * @return id associated with the specified action, <code>null</code> if action
     *         is not registered
     * @throws java.lang.IllegalArgumentException
     *         if <code>action</code> is <code>null</code>
     */
    public String getId(Action action);

    /**
     * Registers the specified action with the specified id. Note that IDE keymaps
     * processing deals only with registered actions.
     *
     * @param actionId
     *         Id to associate with the action
     * @param action
     *         Action to register
     */
    public void registerAction(String actionId, Action action);

    /**
     * Registers the specified action with the specified id.
     *
     * @param actionId
     *         Id to associate with the action
     * @param action
     *         Action to register
     * @param extensionId
     *         Identifier of the extension owning the action. Used to show the actions in the
     *         correct place under the "Plugins" node in the "Keymap" settings pane and similar dialogs.
     */
    public void registerAction(String actionId, Action action, String extensionId);

    /**
     * Unregisters the action with the specified actionId.
     *
     * @param actionId
     *         Id of the action to be unregistered
     */
    public void unregisterAction(String actionId);

    /**
     * Returns the list of all registered action IDs with the specified prefix.
     *
     * @return all action <code>id</code>s which have the specified prefix.
     */
    public String[] getActionIds(String idPrefix);

    /**
     * Checks if the specified action ID represents an action group and not an individual action.
     * Calling this method does not cause instantiation of a specific action class corresponding
     * to the action ID.
     *
     * @param actionId
     *         the ID to check.
     * @return true if the ID represents an action group, false otherwise.
     */
    public boolean isGroup(String actionId);
}
