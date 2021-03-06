package org.darrylmiles.example.ee7.jca.eis.rar.spi;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.naming.CompositeName;
import javax.naming.InitialContext;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NamingException;
import javax.resource.ResourceException;
import javax.resource.spi.ActivationSpec;
import javax.resource.spi.BootstrapContext;
import javax.resource.spi.Connector;
import javax.resource.spi.ResourceAdapter;
import javax.resource.spi.ResourceAdapterInternalException;
import javax.resource.spi.TransactionSupport.TransactionSupportLevel;
import javax.resource.spi.UnavailableException;
import javax.resource.spi.XATerminator;
import javax.resource.spi.endpoint.MessageEndpointFactory;
import javax.resource.spi.work.WorkException;
import javax.resource.spi.work.WorkManager;
import javax.transaction.xa.XAResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.darrylmiles.example.ee7.jca.eis.rar.Constants;
import org.darrylmiles.example.ee7.jca.eis.rar.driver.PerMinuteTimerTask;
import org.darrylmiles.example.ee7.jca.eis.rar.driver.EisConnection;

@Connector(
	description = Constants.ADAPTER_SHORT_DESCRIPTION,
	displayName = Constants.ADAPTER_NAME,
	vendorName = Constants.ADAPTER_VENDOR_NAME,
	eisType = Constants.ADAPTER_EIS_TYPE,
	licenseDescription = Constants.ADAPTER_LICENSE_DESCRIPTION,
	transactionSupport = TransactionSupportLevel.NoTransaction,
	version = Constants.ADAPTER_VERSION)
public class ResourceAdapterImpl implements ResourceAdapter, Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 3818084587454142307L;

	private static final Logger log = LoggerFactory.getLogger(ResourceAdapterImpl.class);

	private transient WorkManager workManager;
	private transient XATerminator xaTerminator;
	private transient Timer timer;

	private transient EisConnection eisConnection;
	private transient PerMinuteTimerTask perMinuteTimerTask;

	private transient ResourceAdapterCustomImpl resourceAdapterCustomImpl;

	private transient ConcurrentLinkedQueue<EndpointKey> endpointKeyList;

	private Name jndiName;

	public ResourceAdapterImpl() {
		endpointKeyList = new ConcurrentLinkedQueue<EndpointKey>();
	}

	public void start() {
		resourceAdapterCustomImpl = new ResourceAdapterCustomImpl(this);

		try {
			eisConnection = new EisConnection();
			workManager.startWork(eisConnection);

			try {
				boolean bf = eisConnection.waitForStartupReady(10000);
				log.debug("eisConnection={} bf={} isStartupReadyFlag={} isShutdownFlag={}", eisConnection, bf, eisConnection.isStartupReadyFlag(), eisConnection.isShutdownFlag());
			} catch (InterruptedException e) {
				log.warn("", e);
			}
		} catch (WorkException ex) {
			log.debug("", ex);
		}
	}


	@Override
	public void start(BootstrapContext ctx) throws ResourceAdapterInternalException {
		log.debug("");

		workManager = ctx.getWorkManager();
		xaTerminator = ctx.getXATerminator();
		try {
			timer = ctx.createTimer();

			perMinuteTimerTask = new PerMinuteTimerTask();
			perMinuteTimerTask.init();
			timer.schedule(perMinuteTimerTask, perMinuteTimerTask.getDelay(), perMinuteTimerTask.getPeriod());
		} catch (UnavailableException e) {
			log.warn("", e);
			//throw new ResourceAdapterInternalException(e);
		}
		bind();
		log.debug("workManager={}, xaTerminator={}, timer={}", workManager, xaTerminator, timer);
	}

	@Override
	public void stop() {
		log.debug("");
		unbind();
	}

	// This method is invoked when a listening consumer is registered
	@Override
	public void endpointActivation(MessageEndpointFactory endpointFactory, ActivationSpec spec) throws ResourceException {
		log.debug("endpointFactory={}, spec={}", endpointFactory, spec);
		EndpointKey endpointKey = new EndpointKey(this, endpointFactory, spec);
		endpointKeyList.add(endpointKey);
		log.debug("ADDED {}", endpointFactory);
	}

	@Override
	public void endpointDeactivation(MessageEndpointFactory endpointFactory, ActivationSpec spec) {
		log.debug("endpointFactory={}, spec={}", endpointFactory, spec);
		EndpointKey[] endpointKeys = findAndRemoveEndpointKey(endpointFactory, spec);
		for(EndpointKey ek : endpointKeys)
			log.debug("REMOVED {}", ek);
	}

	//@Nonnull
	private EndpointKey[] findAndRemoveEndpointKey(MessageEndpointFactory endpointFactory, ActivationSpec spec) {
		EndpointKey[] endpointKeys = findEndpointKeys(endpointFactory, spec);

		Collection<EndpointKey> coll = new LinkedList<EndpointKey>();
		for(EndpointKey ek : endpointKeys)
			coll.add(ek);

		endpointKeyList.removeAll(coll);
		return coll.toArray(new EndpointKey[coll.size()]);	// return only removed keys
	}

	//@Nonnull
	private EndpointKey[] findEndpointKeys(MessageEndpointFactory endpointFactory, ActivationSpec spec) {
		Set<EndpointKey> set = new HashSet<EndpointKey>();
		set.addAll(endpointKeyList);		// atomic copy ?

		LinkedList<EndpointKey> list = new LinkedList<EndpointKey>();
		for(EndpointKey ek : set) {
			if(ek.match(this, endpointFactory, spec)) {
				list.add(ek);
			}
		}
		return list.toArray(new EndpointKey[list.size()]);
	}

	@Override
	public XAResource[] getXAResources(ActivationSpec[] specs) throws ResourceException {
		log.debug("specs={}", (Object[]) specs);
		if(specs != null) {
			int i = 0;
			for(ActivationSpec as : specs) {
				log.debug("specs[{}].ra={}", i, as.getResourceAdapter());
				i++;
			}
		}
		return null;		// can this return null ?  new XAResource[0] ?
	}

	private Boolean bind() {
		Boolean bf = null;
		synchronized (this) {
			if(jndiName != null)
				return bf;
			Name newJndiName;
			try {
				newJndiName = new CompositeName(Constants.JNDI_NAME);
			} catch (InvalidNameException e) {
				throw new RuntimeException(e);		// convert unchecked
			}
			bf = Boolean.FALSE;
			synchronized (newJndiName) {
				try {
					InitialContext ctx = new InitialContext();
					ctx.bind(jndiName, resourceAdapterCustomImpl);
					jndiName = newJndiName;
					bf = Boolean.TRUE;
				} catch (NamingException e) {
				}
			}
		}
		return bf;
	}

	private Boolean unbind() {
		Boolean bf = null;
		if(jndiName != null) {
			bf = Boolean.FALSE;
			synchronized(jndiName) {
				try {
					InitialContext ctx = new InitialContext();
					ctx.unbind(jndiName);
					bf = Boolean.TRUE;
				} catch (NamingException e) {
				} finally {
					jndiName = null;
				}
			}
		}
		return bf;
	}

	// Required EE contract to implement #equals(Object)
	public boolean equals(Object o) {
		if(o == null)
			return false;
		if(o == this)
			return true;
		if((o instanceof ResourceAdapterImpl) == false)
			return false;
		return false;
	}

	// Required EE contract to implement #hashCode()
	public int hashCode() {
		return super.hashCode();
	}
}
