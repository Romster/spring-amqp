/*
 * Copyright 2002-2010 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.amqp.rabbit.connection;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.transaction.support.ResourceHolderSynchronization;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.rabbitmq.client.Channel;
/**
 * Helper class for managing a Spring based Rabbit {@link org.springframework.amqp.rabbit.connection.ConnectionFactory},
 * in particular for obtaining transactional Rabbit resources for a given ConnectionFactory.
 * 
 * <p>
 * Mainly for internal use within the framework. Used by {@link org.springframework.amqp.rabbit.core.RabbitTemplate} as
 * well as {@link org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer}.
 * 
 * @author Mark Fisher
 */
public class ConnectionFactoryUtils {

	private static final Log logger = LogFactory.getLog(ConnectionFactoryUtils.class);

	/**
	 * Release the given Connection by closing it.
	 */
	public static void releaseConnection(Connection con) {
		if (con == null) {
			return;
		}
		try {
			con.close();
		} catch (Throwable ex) {
			logger.debug("Could not close RabbitMQ Connection", ex);
		}
	}

	/**
	 * Determine whether the given RabbitMQ Channel is transactional, that is, bound to the current thread by Spring's
	 * transaction facilities.
	 * @param channel the RabbitMQ Channel to check
	 * @param connectionFactory the RabbitMQ ConnectionFactory that the Channel originated from
	 * @return whether the Channel is transactional
	 */
	public static boolean isChannelTransactional(Channel channel, ConnectionFactory connectionFactory) {
		if (channel == null || connectionFactory == null) {
			return false;
		}
		RabbitResourceHolder resourceHolder = (RabbitResourceHolder) TransactionSynchronizationManager
				.getResource(connectionFactory);
		return (resourceHolder != null && resourceHolder.containsChannel(channel));
	}

	/**
	 * Obtain a RabbitMQ Channel that is synchronized with the current transaction, if any.
	 * @param connectionFactory the ConnectionFactory to obtain a Channel for
	 * @param synchedLocalTransactionAllowed whether to allow for a local RabbitMQ transaction that is synchronized with
	 * a Spring-managed transaction (where the main transaction might be a JDBC-based one for a specific DataSource, for
	 * example), with the RabbitMQ transaction committing right after the main transaction. If not allowed, the given
	 * ConnectionFactory needs to handle transaction enlistment underneath the covers.
	 * @return the transactional Channel, or <code>null</code> if none found
	 */
	public static RabbitResourceHolder getTransactionalResourceHolder(final ConnectionFactory connectionFactory,
			final boolean synchedLocalTransactionAllowed) {

		RabbitResourceHolder holder = doGetTransactionalResourceHolder(connectionFactory, new ResourceFactory() {
					public Channel getChannel(RabbitResourceHolder holder) {
						return holder.getChannel();
					}

					public Connection getConnection(RabbitResourceHolder holder) {
						return holder.getConnection();
					}

					public Connection createConnection() throws IOException {
						return connectionFactory.createConnection();
					}

					public Channel createChannel(Connection con) throws IOException {
						return con.createChannel(synchedLocalTransactionAllowed);
					}

					public boolean isSynchedLocalTransactionAllowed() {
						return synchedLocalTransactionAllowed;
					}
				});
		if (synchedLocalTransactionAllowed) {
			holder.declareTransactional();
		}
		return holder;
	}

	/**
	 * Obtain a RabbitMQ Channel that is synchronized with the current transaction, if any.
	 * @param connectionFactory the RabbitMQ ConnectionFactory to bind for (used as TransactionSynchronizationManager
	 * key)
	 * @param resourceFactory the ResourceFactory to use for extracting or creating RabbitMQ resources
	 * @return the transactional Channel, or <code>null</code> if none found
	 */
	private static RabbitResourceHolder doGetTransactionalResourceHolder(ConnectionFactory connectionFactory,
			ResourceFactory resourceFactory) {

		Assert.notNull(connectionFactory, "ConnectionFactory must not be null");
		Assert.notNull(resourceFactory, "ResourceFactory must not be null");

		RabbitResourceHolder resourceHolder = (RabbitResourceHolder) TransactionSynchronizationManager
				.getResource(connectionFactory);
		if (resourceHolder != null) {
			Channel channel = resourceFactory.getChannel(resourceHolder);
			if (channel != null) {
				return resourceHolder;
			}
		}
		RabbitResourceHolder resourceHolderToUse = resourceHolder;
		if (resourceHolderToUse == null) {
			resourceHolderToUse = new RabbitResourceHolder();
		}
		Connection con = resourceFactory.getConnection(resourceHolderToUse);
		Channel channel = null;
		try {
			boolean isExistingCon = (con != null);
			if (!isExistingCon) {
				con = resourceFactory.createConnection();
				resourceHolderToUse.addConnection(con);
			}
			channel = resourceFactory.createChannel(con);
			resourceHolderToUse.addChannel(channel, con);

			if (resourceHolderToUse != resourceHolder) {
				bindResourceToTransaction(resourceHolderToUse, connectionFactory,
						resourceFactory.isSynchedLocalTransactionAllowed());
			}
			if (isChannelTransactional(channel, connectionFactory)) {
				// It is externally transacted and was just created so we want to start the transaction
				channel.txSelect();
			}

			return resourceHolderToUse;

		} catch (IOException ex) {
			if (channel != null) {
				try {
					channel.close();
				} catch (Throwable ex2) {
					// ignore
				}
			}
			if (con != null) {
				try {
					con.close();
				} catch (Throwable ex2) {
					// ignore
				}
			}
			throw new AmqpIOException(ex);
		}
	}

	public static void releaseResources(RabbitResourceHolder resourceHolder) {
		if (resourceHolder == null || resourceHolder.isSynchronizedWithTransaction()) {
			return;
		}
		RabbitUtils.closeChannel(resourceHolder.getChannel());
		releaseConnection(resourceHolder.getConnection());
	}

	public static void bindResourceToTransaction(RabbitResourceHolder resourceHolder,
			ConnectionFactory connectionFactory, boolean synched) {
		if (TransactionSynchronizationManager.hasResource(connectionFactory)
				|| !TransactionSynchronizationManager.isActualTransactionActive() || !synched) {
			return;
		}
		TransactionSynchronizationManager.bindResource(connectionFactory, resourceHolder);
		resourceHolder.setSynchronizedWithTransaction(true);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new RabbitResourceSynchronization(resourceHolder,
					connectionFactory, synched));
		}
	}

	/**
	 * 
	 */
	public static void registerDeliveryTag(ConnectionFactory connectionFactory, Channel channel, Long tag)
			throws IOException {

		Assert.notNull(connectionFactory, "ConnectionFactory must not be null");

		RabbitResourceHolder resourceHolder = (RabbitResourceHolder) TransactionSynchronizationManager
				.getResource(connectionFactory);
		if (resourceHolder != null) {
			resourceHolder.addDeliveryTag(channel, tag);
		}
	}

	/**
	 * Callback interface for resource creation. Serving as argument for the <code>doGetTransactionalChannel</code>
	 * method.
	 */
	public interface ResourceFactory {

		/**
		 * Fetch an appropriate Channel from the given RabbitResourceHolder.
		 * @param holder the RabbitResourceHolder
		 * @return an appropriate Channel fetched from the holder, or <code>null</code> if none found
		 */
		Channel getChannel(RabbitResourceHolder holder);

		/**
		 * Fetch an appropriate Connection from the given RabbitResourceHolder.
		 * @param holder the RabbitResourceHolder
		 * @return an appropriate Connection fetched from the holder, or <code>null</code> if none found
		 */
		Connection getConnection(RabbitResourceHolder holder);

		/**
		 * Create a new RabbitMQ Connection for registration with a RabbitResourceHolder.
		 * @return the new RabbitMQ Connection
		 * @throws IOException if thrown by RabbitMQ API methods
		 */
		Connection createConnection() throws IOException;

		/**
		 * Create a new RabbitMQ Session for registration with a RabbitResourceHolder.
		 * @param con the RabbitMQ Connection to create a Channel for
		 * @return the new RabbitMQ Channel
		 * @throws IOException if thrown by RabbitMQ API methods
		 */
		Channel createChannel(Connection con) throws IOException;

		/**
		 * Return whether to allow for a local RabbitMQ transaction that is synchronized with a Spring-managed
		 * transaction (where the main transaction might be a JDBC-based one for a specific DataSource, for example),
		 * with the RabbitMQ transaction committing right after the main transaction.
		 * @return whether to allow for synchronizing a local RabbitMQ transaction
		 */
		boolean isSynchedLocalTransactionAllowed();
	}

	/**
	 * Callback for resource cleanup at the end of a non-native RabbitMQ transaction (e.g. when participating in a
	 * JtaTransactionManager transaction).
	 * @see org.springframework.transaction.jta.JtaTransactionManager
	 */
	private static class RabbitResourceSynchronization extends
			ResourceHolderSynchronization<RabbitResourceHolder, Object> {

		private final boolean transacted;

		private final RabbitResourceHolder resourceHolder;

		public RabbitResourceSynchronization(RabbitResourceHolder resourceHolder, Object resourceKey, boolean transacted) {
			super(resourceHolder, resourceKey);
			this.resourceHolder = resourceHolder;
			this.transacted = transacted;
		}

		protected boolean shouldReleaseBeforeCompletion() {
			return !this.transacted;
		}

		protected void processResourceAfterCommit(RabbitResourceHolder resourceHolder) {
			resourceHolder.commitAll();
		}

		@Override
		public void afterCompletion(int status) {
			if (status != TransactionSynchronization.STATUS_COMMITTED) {
				resourceHolder.rollbackAll();
			}
			super.afterCompletion(status);
		}

		protected void releaseResource(RabbitResourceHolder resourceHolder, Object resourceKey) {
			ConnectionFactoryUtils.releaseResources(resourceHolder);
		}
	}

}
