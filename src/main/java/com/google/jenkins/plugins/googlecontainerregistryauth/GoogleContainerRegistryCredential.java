/*
 * Copyright 2015 Google Inc. All Rights Reserved.
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

package com.google.jenkins.plugins.googlecontainerregistryauth;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.GeneralSecurityException;
import java.util.List;

import javax.annotation.Nullable;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import static com.google.common.base.Preconditions.checkNotNull;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.DomainRestrictedCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.google.api.client.util.Strings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.jenkins.plugins.credentials.domains.DomainRequirementProvider;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;

import hudson.Extension;
import hudson.util.Secret;

import jenkins.model.Jenkins;

import net.sf.json.JSONObject;

/**
 * This new kind of credential provides an embedded
 * {@link GoogleRobotCredentials} as a username and password for use with a
 * {@link org.jenkinsci.plugins.dockerbuildstep.cmd.DockerCommand}.
 * <p>
 * BACKGROUND: Google Container Registry accepts a
 * username/password combination that is truly "_token:<oauth_token>".
 *
 * This credential wraps a service account credential to provide it in this
 * manner as a {@code StandardUsernamePasswordCredentials} for usage with the
 * new {@code Credentials}-aware
 * {@link org.jenkinsci.plugins.dockerbuildstep.cmd.DockerCommand}
 * plugins.
 */
@NameWith(value = GoogleContainerRegistryCredential.NameProvider.class,
    priority = 99)
public class GoogleContainerRegistryCredential
    extends BaseStandardCredentials implements DomainRestrictedCredentials,
    StandardUsernamePasswordCredentials {

  private final String credentialsId;
  @VisibleForTesting GoogleContainerRegistryCredentialModule module;

  @DataBoundConstructor
  public GoogleContainerRegistryCredential(String credentialsId,
      @Nullable GoogleContainerRegistryCredentialModule module) {
    super(CredentialsScope.GLOBAL, "gcr:" + credentialsId,
        "Google Container Registry" /* description */);
    this.credentialsId = checkNotNull(credentialsId);
    if (module != null) {
      this.module = module;
    } else {
      this.module = new GoogleContainerRegistryCredentialModule();
    }
  }

  /**
   * Return the unique ID of the inner {@link GoogleRobotCredentials} that this
   * Username/Password proxy is wrapping.
   */
  public String getCredentialsId() {
    return credentialsId;
  }

  /**
   * Retrieve our wrapped credentials based on the above ID we store.
   */
  @Nullable
  public GoogleRobotCredentials getCredentials() {
    return isOnMaster() ?
        GoogleRobotCredentials.getById(getCredentialsId()) : null;
  }

  /**
   * Detect whether we are on the master, to determine how to
   * serialize things.
   */
  private boolean isOnMaster() {
    return Jenkins.getInstance() != null;
  }

  /**
   * This type of credentials only works for authenticating against registry's
   * server. For production server, it is "gcr.io".
   */
  @Override
  public boolean matches(List<DomainRequirement> requirements) {
    return module.matches(requirements);
  }

  /**
   * Support writing our credential to the wire or disk.
   *
   * NOTE: If we aren't on the master, we are expected to provide the embedded
   * credential, along with a module the recipient can use to establish identity
   *
   * It is also worth noting that this serializes superfluous information when
   * we are writing to disk.
   */
  private void writeObject(ObjectOutputStream oos) throws IOException {
    oos.defaultWriteObject();
    // Pass a remotable version of our module, tailored to this local
    // credential, to the receiving readObject method.
    // NOTE: If this is simply serializing to disk, the readObject will ignore
    // this when reading it back in.
    try {
      oos.writeObject(module.forRemote(getCredentials()));
    } catch (GeneralSecurityException e) {
      throw new IOException(e);
    }
  }

  /**
   * Support reading our credential from the wire or disk.
   *
   * NOTE: If we aren't on the master, we expect the embedded credential
   * to be provided, along with a module we can use to establish identity.
   */
  private void readObject(ObjectInputStream ois)
      throws IOException, ClassNotFoundException {
    ois.defaultReadObject();
    if (!isOnMaster()) {
      // Read in the remotable module, which we will use for things like
      // retrieving identity and credentials.
      this.module =
          (GoogleContainerRegistryCredentialModule) ois.readObject();
    }
  }

  /** {@inheritDoc} */
  @Override
  public String getDescription() {
    return CredentialsNameProvider.name(getCredentials());
  }

  /** {@inheritDoc} */
  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  /**
   * We do not have a descriptor, so that we won't show up in the user interface
   * as a credential that can be explicitly created, so we will not be
   * discovered by the DescribableDomainRequirementProvider.  Instead, implement
   * our own trivial provider.
   */
  @Extension
  public static class EnclosingDomainRequirementProvider
      extends DomainRequirementProvider {
    /** {@inheritDoc} */
    @Override
    protected <T extends DomainRequirement> List<T> provide(Class<T> type) {
      @Nullable T requirement =
          of(GoogleContainerRegistryCredential.class, type);
      return (requirement == null) ? ImmutableList.<T>of()
          : ImmutableList.of(requirement);
    }
  }

  /**
   * Provide a name that the user will understand, in the dropdown
   * shown by {@link org.jenkinsci.plugins.dockerbuildstep.cmd.DockerCommand}.
   */
  public static class NameProvider
      extends CredentialsNameProvider<GoogleContainerRegistryCredential> {
    /** {@inheritDoc} */
    @Override
    public String getName(GoogleContainerRegistryCredential c) {
      return Messages.GoogleContainerRegistryCredential_ListingWrapper(
          c.getDescription());
    }
  }

  /** {@inheritDoc} */
  @Override
  public String getUsername() {
    return module.getIdentity(getCredentials());
  }

  /** {@inheritDoc} */
  @Override
  public Secret getPassword() {
    return module.getToken(getCredentials());
  }

  public String getGcrServer() {
    return ((DescriptorImpl) super.getDescriptor()).getGcrServer();
  }

  /**
   * Descriptor class for its global configuration.
   */
  @Extension
  public static final class DescriptorImpl extends CredentialsDescriptor {

    private static final String GCR_SERVER = "gcr.io";

    public DescriptorImpl() {
      load();
    }

    /** Display name to prefer in the context of global settings. */
    public String getGlobalDisplayName() {
      return Messages.
          GoogleContainerRegistryCredential_GlobalDisplayName();
    }

    @Override
    /** {@inheritDoc} */
    public String getDisplayName() {
      return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean configure(StaplerRequest req, JSONObject json)
        throws FormException {
      json = json.getJSONObject(getGlobalDisplayName());
      gcrServer = json.has("gcrServer") ?
          json.getString("gcrServer") : null;
      save();
      return true;
    }

    /**
     * Retrieve the Google Registry Container server URL.
     */
    @Nullable public String getGcrServer() {
      return Strings.isNullOrEmpty(gcrServer) ? GCR_SERVER : gcrServer;
    }

    private String gcrServer = null;
  }
}