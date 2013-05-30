/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.state;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.inject.persist.Transactional;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ServiceComponentHostNotFoundException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.ServiceComponentResponse;
import org.apache.ambari.server.orm.dao.*;
import org.apache.ambari.server.orm.entities.*;
import org.apache.ambari.server.state.cluster.ClusterImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceComponentImpl implements ServiceComponent {

  private final static Logger LOG =
      LoggerFactory.getLogger(ServiceComponentImpl.class);
  
  ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

  private final Service service;

  @Inject
  private Gson gson;
  @Inject
  private ServiceComponentDesiredStateDAO serviceComponentDesiredStateDAO;
  @Inject
  private ClusterServiceDAO clusterServiceDAO;
  @Inject
  private HostComponentStateDAO hostComponentStateDAO;
  @Inject
  private HostComponentDesiredStateDAO hostComponentDesiredStateDAO;
  @Inject
  private ServiceComponentHostFactory serviceComponentHostFactory;
  @Inject
  private AmbariMetaInfo ambariMetaInfo;
  @Inject
  private ComponentConfigMappingDAO componentConfigMappingDAO;

  boolean persisted = false;
  private ServiceComponentDesiredStateEntity desiredStateEntity;

  // [ type -> versionTag ]
  private Map<String, String>  desiredConfigs;

  private Map<String, ServiceComponentHost> hostComponents;

  private final boolean isClientComponent;

  private void init() {
    // TODO load during restart
    // initialize from DB
  }

  @AssistedInject
  public ServiceComponentImpl(@Assisted Service service,
      @Assisted String componentName, Injector injector) throws AmbariException {
    injector.injectMembers(this);
    this.service = service;
    this.desiredStateEntity = new ServiceComponentDesiredStateEntity();
    desiredStateEntity.setComponentName(componentName);
    desiredStateEntity.setDesiredState(State.INIT);

    this.desiredConfigs = new HashMap<String, String>();
    setDesiredStackVersion(service.getDesiredStackVersion());

    this.hostComponents = new HashMap<String, ServiceComponentHost>();

    StackId stackId = service.getDesiredStackVersion();
    ComponentInfo compInfo = ambariMetaInfo.getComponentCategory(
        stackId.getStackName(), stackId.getStackVersion(), service.getName(),
        componentName);
    if (compInfo == null) {
      throw new RuntimeException("Trying to create a ServiceComponent"
          + " not recognized in stack info"
          + ", clusterName=" + service.getCluster().getClusterName()
          + ", serviceName=" + service.getName()
          + ", componentName=" + componentName
          + ", stackInfo=" + stackId.getStackId());
    }
    this.isClientComponent = compInfo.isClient();

    init();
  }

  @AssistedInject
  public ServiceComponentImpl(@Assisted Service service,
                              @Assisted ServiceComponentDesiredStateEntity serviceComponentDesiredStateEntity,
                              Injector injector) throws AmbariException {
    injector.injectMembers(this);
    this.service = service;
    this.desiredStateEntity = serviceComponentDesiredStateEntity;

    this.desiredConfigs = new HashMap<String, String>();

    this.hostComponents = new HashMap<String, ServiceComponentHost>();
    for (HostComponentStateEntity hostComponentStateEntity : desiredStateEntity.getHostComponentStateEntities()) {
      HostComponentDesiredStateEntityPK pk = new HostComponentDesiredStateEntityPK();
      pk.setClusterId(hostComponentStateEntity.getClusterId());
      pk.setServiceName(hostComponentStateEntity.getServiceName());
      pk.setComponentName(hostComponentStateEntity.getComponentName());
      pk.setHostName(hostComponentStateEntity.getHostName());

      HostComponentDesiredStateEntity hostComponentDesiredStateEntity = hostComponentDesiredStateDAO.findByPK(pk);

      hostComponents.put(hostComponentStateEntity.getHostName(),
          serviceComponentHostFactory.createExisting(this,
              hostComponentStateEntity, hostComponentDesiredStateEntity));
    }

    for (ComponentConfigMappingEntity entity : desiredStateEntity.getComponentConfigMappingEntities()) {
      desiredConfigs.put(entity.getConfigType(), entity.getVersionTag());
    }

    StackId stackId = service.getDesiredStackVersion();
    ComponentInfo compInfo = ambariMetaInfo.getComponentCategory(
        stackId.getStackName(), stackId.getStackVersion(), service.getName(),
        getName());
    if (compInfo == null) {
      throw new RuntimeException("Trying to create a ServiceComponent"
          + " not recognized in stack info"
          + ", clusterName=" + service.getCluster().getClusterName()
          + ", serviceName=" + service.getName()
          + ", componentName=" + getName()
          + ", stackInfo=" + stackId.getStackId());
    }
    this.isClientComponent = compInfo.isClient();

    persisted = true;
  }

  @Override
  public String getName() {
    readWriteLock.readLock().lock();
    try {
      return desiredStateEntity.getComponentName();
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public String getServiceName() {
    readWriteLock.readLock().lock();
    try {
      return service.getName();
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public long getClusterId() {
    readWriteLock.readLock().lock();
    try {
      return this.service.getClusterId();
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public Map<String, ServiceComponentHost>
      getServiceComponentHosts() {
    readWriteLock.readLock().lock();
    try {
      return Collections.unmodifiableMap(hostComponents);
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public void addServiceComponentHosts(
      Map<String, ServiceComponentHost> hostComponents) throws AmbariException {
    readWriteLock.writeLock().lock();
    try {
      // TODO validation
      for (Entry<String, ServiceComponentHost> entry :
          hostComponents.entrySet()) {
        if (!entry.getKey().equals(entry.getValue().getHostName())) {
          throw new AmbariException("Invalid arguments in map"
              + ", hostname does not match the key in map");
        }
      }
      for (ServiceComponentHost sch : hostComponents.values()) {
        addServiceComponentHost(sch);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public void addServiceComponentHost(
      ServiceComponentHost hostComponent) throws AmbariException {
    readWriteLock.writeLock().lock();
    try {
      // TODO validation
      // TODO ensure host belongs to cluster
      if (LOG.isDebugEnabled()) {
        LOG.debug("Adding a ServiceComponentHost to ServiceComponent"
            + ", clusterName=" + service.getCluster().getClusterName()
            + ", clusterId=" + service.getCluster().getClusterId()
            + ", serviceName=" + service.getName()
            + ", serviceComponentName=" + getName()
            + ", hostname=" + hostComponent.getHostName());
      }
      if (hostComponents.containsKey(hostComponent.getHostName())) {
        throw new AmbariException("Cannot add duplicate ServiceComponentHost"
            + ", clusterName=" + service.getCluster().getClusterName()
            + ", clusterId=" + service.getCluster().getClusterId()
            + ", serviceName=" + service.getName()
            + ", serviceComponentName=" + getName()
            + ", hostname=" + hostComponent.getHostName());
      }
      // FIXME need a better approach of caching components by host
      ClusterImpl clusterImpl = (ClusterImpl) service.getCluster();
      clusterImpl.addServiceComponentHost(hostComponent);
      this.hostComponents.put(hostComponent.getHostName(), hostComponent);
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public ServiceComponentHost addServiceComponentHost(
      String hostName) throws AmbariException {
    readWriteLock.writeLock().lock();
    try {
      // TODO validation
      // TODO ensure host belongs to cluster
      if (LOG.isDebugEnabled()) {
        LOG.debug("Adding a ServiceComponentHost to ServiceComponent"
            + ", clusterName=" + service.getCluster().getClusterName()
            + ", clusterId=" + service.getCluster().getClusterId()
            + ", serviceName=" + service.getName()
            + ", serviceComponentName=" + getName()
            + ", hostname=" + hostName);
      }
      if (hostComponents.containsKey(hostName)) {
        throw new AmbariException("Cannot add duplicate ServiceComponentHost"
            + ", clusterName=" + service.getCluster().getClusterName()
            + ", clusterId=" + service.getCluster().getClusterId()
            + ", serviceName=" + service.getName()
            + ", serviceComponentName=" + getName()
            + ", hostname=" + hostName);
      }
      ServiceComponentHost hostComponent =
          serviceComponentHostFactory.createNew(this, hostName, this.isClientComponent());
      // FIXME need a better approach of caching components by host
      ClusterImpl clusterImpl = (ClusterImpl) service.getCluster();
      clusterImpl.addServiceComponentHost(hostComponent);

      this.hostComponents.put(hostComponent.getHostName(), hostComponent);

      return hostComponent;
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public ServiceComponentHost getServiceComponentHost(String hostname)
    throws AmbariException {
    readWriteLock.readLock().lock();
    try {
      if (!hostComponents.containsKey(hostname)) {
        throw new ServiceComponentHostNotFoundException(getClusterName(),
            getServiceName(), getName(), hostname);
      }
      return this.hostComponents.get(hostname);
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public State getDesiredState() {
    readWriteLock.readLock().lock();
    try {
      return desiredStateEntity.getDesiredState();
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public void setDesiredState(State state) {
    readWriteLock.writeLock().lock();
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting DesiredState of Service"
            + ", clusterName=" + service.getCluster().getClusterName()
            + ", clusterId=" + service.getCluster().getClusterId()
            + ", serviceName=" + service.getName()
            + ", serviceComponentName=" + getName()
            + ", oldDesiredState=" + getDesiredState()
            + ", newDesiredState=" + state);
      }
      desiredStateEntity.setDesiredState(state);
      saveIfPersisted();
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public Map<String, Config> getDesiredConfigs() {
    readWriteLock.readLock().lock();
    try {
      Map<String, Config> map = new HashMap<String, Config>();
      for (Entry<String, String> entry : desiredConfigs.entrySet()) {
        Config config = service.getCluster().getConfig(entry.getKey(), entry.getValue());
        if (null != config) {
          map.put(entry.getKey(), config);
        }
      }

      Map<String, Config> svcConfigs = service.getDesiredConfigs();
      for (Entry<String, Config> entry : svcConfigs.entrySet()) {
        if (!map.containsKey(entry.getKey())) {
          map.put(entry.getKey(), entry.getValue());
        }
      }

      return Collections.unmodifiableMap(map);
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public void updateDesiredConfigs(Map<String, Config> configs) {

    readWriteLock.writeLock().lock();
    try {
      for (Entry<String, Config> entry : configs.entrySet()) {
        boolean contains = false;

        for (ComponentConfigMappingEntity componentConfigMappingEntity : desiredStateEntity.getComponentConfigMappingEntities()) {
          if (entry.getKey().equals(componentConfigMappingEntity.getConfigType())) {
            contains = true;
            componentConfigMappingEntity.setTimestamp(new Date().getTime());
            componentConfigMappingEntity.setVersionTag(entry.getValue().getVersionTag());
            if (persisted) {
              componentConfigMappingDAO.merge(componentConfigMappingEntity);
            }
          }
        }

        if (!contains) {
          ComponentConfigMappingEntity newEntity = new ComponentConfigMappingEntity();
          newEntity.setClusterId(desiredStateEntity.getClusterId());
          newEntity.setServiceName(desiredStateEntity.getServiceName());
          newEntity.setComponentName(desiredStateEntity.getComponentName());
          newEntity.setConfigType(entry.getKey());
          newEntity.setVersionTag(entry.getValue().getVersionTag());
          newEntity.setTimestamp(new Date().getTime());
          newEntity.setServiceComponentDesiredStateEntity(desiredStateEntity);
          desiredStateEntity.getComponentConfigMappingEntities().add(newEntity);

        }


        this.desiredConfigs.put(entry.getKey(), entry.getValue().getVersionTag());
      }

      saveIfPersisted();
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public StackId getDesiredStackVersion() {
    readWriteLock.readLock().lock();
    try {
      return gson.fromJson(desiredStateEntity.getDesiredStackVersion(), StackId.class);
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public void setDesiredStackVersion(StackId stackVersion) {
    readWriteLock.writeLock().lock();
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting DesiredStackVersion of Service"
            + ", clusterName=" + service.getCluster().getClusterName()
            + ", clusterId=" + service.getCluster().getClusterId()
            + ", serviceName=" + service.getName()
            + ", serviceComponentName=" + getName()
            + ", oldDesiredStackVersion=" + getDesiredStackVersion()
            + ", newDesiredStackVersion=" + stackVersion);
      }
      desiredStateEntity.setDesiredStackVersion(gson.toJson(stackVersion));
      saveIfPersisted();
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public ServiceComponentResponse convertToResponse() {
    readWriteLock.readLock().lock();
    try {
      ServiceComponentResponse r = new ServiceComponentResponse(
          getClusterId(), service.getCluster().getClusterName(),
          service.getName(), getName(), this.desiredConfigs,
          getDesiredStackVersion().getStackId(),
          getDesiredState().toString());
      return r;
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public String getClusterName() {
    readWriteLock.readLock().lock();
    try {
      return service.getCluster().getClusterName();
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public void debugDump(StringBuilder sb) {
    readWriteLock.readLock().lock();
    try {
      sb.append("ServiceComponent={ serviceComponentName=" + getName()
          + ", clusterName=" + service.getCluster().getClusterName()
          + ", clusterId=" + service.getCluster().getClusterId()
          + ", serviceName=" + service.getName()
          + ", desiredStackVersion=" + getDesiredStackVersion()
          + ", desiredState=" + getDesiredState().toString()
          + ", hostcomponents=[ ");
      boolean first = true;
      for (ServiceComponentHost sch : hostComponents.values()) {
        if (!first) {
          sb.append(" , ");
          first = false;
        }
        sb.append("\n        ");
        sch.debugDump(sb);
        sb.append(" ");
      }
      sb.append(" ] }");
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public boolean isPersisted() {
    readWriteLock.readLock().lock();
    try {
      return persisted;
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  public void persist() {
    readWriteLock.writeLock().lock();
    try {
      if (!persisted) {
        persistEntities();
        refresh();
        service.refresh();
        persisted = true;
      } else {
        saveIfPersisted();
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Transactional
  protected void persistEntities() {
    ClusterServiceEntityPK pk = new ClusterServiceEntityPK();
    pk.setClusterId(service.getClusterId());
    pk.setServiceName(service.getName());
    ClusterServiceEntity serviceEntity = clusterServiceDAO.findByPK(pk);

    desiredStateEntity.setClusterServiceEntity(serviceEntity);
    serviceComponentDesiredStateDAO.create(desiredStateEntity);
    clusterServiceDAO.merge(serviceEntity);
  }

  @Override
  @Transactional
  public void refresh() {
    readWriteLock.writeLock().lock();
    try {
      if (isPersisted()) {
        ServiceComponentDesiredStateEntityPK pk = new ServiceComponentDesiredStateEntityPK();
        pk.setComponentName(getName());
        pk.setClusterId(getClusterId());
        pk.setServiceName(getServiceName());
        // TODO: desiredStateEntity is assigned in unway, may be a bug
        desiredStateEntity = serviceComponentDesiredStateDAO.findByPK(pk);
        serviceComponentDesiredStateDAO.refresh(desiredStateEntity);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Transactional
  private void saveIfPersisted() {
    readWriteLock.writeLock().lock();
    try {
      if (isPersisted()) {
        serviceComponentDesiredStateDAO.merge(desiredStateEntity);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public boolean isClientComponent() {
    return this.isClientComponent;
  }

  @Override
  public boolean canBeRemoved() {
    readWriteLock.readLock().lock();
    try {
      if (!getDesiredState().isRemovableState()) {
        return false;
      }

      for (ServiceComponentHost sch : hostComponents.values()) {
        if (!sch.canBeRemoved()) {
          LOG.warn("Found non removable hostcomponent when trying to"
              + " delete service component"
              + ", clusterName=" + getClusterName()
              + ", serviceName=" + getServiceName()
              + ", componentName=" + getName()
              + ", hostname=" + sch.getHostName());
          return false;
        }
      }
      return true;
    } finally {
      readWriteLock.readLock().unlock();
    }

  }

  @Override
  @Transactional
  public void deleteAllServiceComponentHosts()
      throws AmbariException {
    readWriteLock.writeLock().lock();
    try {
      LOG.info("Deleting all servicecomponenthosts for component"
          + ", clusterName=" + getClusterName()
          + ", serviceName=" + getServiceName()
          + ", componentName=" + getName());
      for (ServiceComponentHost sch : hostComponents.values()) {
        if (!sch.canBeRemoved()) {
          throw new AmbariException("Found non removable hostcomponent "
              + " when trying to delete"
              + " all hostcomponents from servicecomponent"
              + ", clusterName=" + getClusterName()
              + ", serviceName=" + getServiceName()
              + ", componentName=" + getName()
              + ", hostname=" + sch.getHostName());
        }
      }

      for (ServiceComponentHost serviceComponentHost : hostComponents.values()) {
        serviceComponentHost.delete();
      }

      hostComponents.clear();
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public void deleteServiceComponentHosts(String hostname)
      throws AmbariException {
    readWriteLock.writeLock().lock();
    try {
      ServiceComponentHost sch = getServiceComponentHost(hostname);
      LOG.info("Deleting servicecomponenthost for cluster"
          + ", clusterName=" + getClusterName()
          + ", serviceName=" + getServiceName()
          + ", componentName=" + getName()
          + ", hostname=" + sch.getHostName());
      if (!sch.canBeRemoved()) {
        throw new AmbariException("Could not delete hostcomponent from cluster"
            + ", clusterName=" + getClusterName()
            + ", serviceName=" + getServiceName()
            + ", componentName=" + getName()
            + ", hostname=" + sch.getHostName());
      }
      sch.delete();
      hostComponents.remove(hostname);

      // FIXME need a better approach of caching components by host
      ClusterImpl clusterImpl = (ClusterImpl) service.getCluster();
      clusterImpl.removeServiceComponentHost(sch);
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  public void deleteDesiredConfigs(Set<String> configTypes) {
    readWriteLock.writeLock().lock();
    try {
      componentConfigMappingDAO.removeByType(configTypes);
      for (String configType : configTypes) {
        desiredConfigs.remove(configType);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Override
  @Transactional
  public void delete() throws AmbariException {
    readWriteLock.writeLock().lock();
    try {
      deleteAllServiceComponentHosts();

      if (persisted) {
        removeEntities();
        persisted = false;
      }

      desiredConfigs.clear();
    } finally {
      readWriteLock.writeLock().unlock();
    }

  }

  @Transactional
  protected void removeEntities() throws AmbariException {
    ServiceComponentDesiredStateEntityPK pk = new ServiceComponentDesiredStateEntityPK();
    pk.setClusterId(getClusterId());
    pk.setComponentName(getName());
    pk.setServiceName(getServiceName());

    serviceComponentDesiredStateDAO.removeByPK(pk);
  }

}
