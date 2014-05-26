package com.netflix.dyno.connectionpool.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Collections2;
import com.google.common.util.concurrent.ListenableFuture;
import com.netflix.dyno.connectionpool.AsyncOperation;
import com.netflix.dyno.connectionpool.Connection;
import com.netflix.dyno.connectionpool.ConnectionContext;
import com.netflix.dyno.connectionpool.ConnectionFactory;
import com.netflix.dyno.connectionpool.ConnectionObservor;
import com.netflix.dyno.connectionpool.ConnectionPool;
import com.netflix.dyno.connectionpool.ConnectionPoolConfiguration;
import com.netflix.dyno.connectionpool.ConnectionPoolMonitor;
import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.HostConnectionPool;
import com.netflix.dyno.connectionpool.HostConnectionStats;
import com.netflix.dyno.connectionpool.Operation;
import com.netflix.dyno.connectionpool.OperationResult;
import com.netflix.dyno.connectionpool.RetryPolicy;
import com.netflix.dyno.connectionpool.RetryPolicy.RetryPolicyFactory;
import com.netflix.dyno.connectionpool.exception.DynoConnectException;
import com.netflix.dyno.connectionpool.exception.DynoException;
import com.netflix.dyno.connectionpool.exception.FatalConnectionException;
import com.netflix.dyno.connectionpool.exception.NoAvailableHostsException;
import com.netflix.dyno.connectionpool.exception.PoolExhaustedException;
import com.netflix.dyno.connectionpool.exception.ThrottledException;
import com.netflix.dyno.connectionpool.impl.ConnectionPoolConfigurationImpl.ErrorRateMonitorConfigImpl;

public class ConnectionPoolImpl<CL> implements ConnectionPool<CL> {

	private static final Logger Logger = LoggerFactory.getLogger(ConnectionPoolImpl.class);
	
	private final ConcurrentHashMap<Host, HostConnectionPool<CL>> cpMap = new ConcurrentHashMap<Host, HostConnectionPool<CL>>();
	private final ConnectionPoolHealthTracker cpHealthTracker = new ConnectionPoolHealthTracker();
	
	private final ConnectionFactory<CL> connFactory; 
	private final ConnectionPoolConfiguration cpConfiguration; 
	private final ConnectionPoolMonitor cpMonitor; 
	
	private final ExecutorService recoveryThreadPool = Executors.newFixedThreadPool(1);
	
	private final HostSelectionStrategy<CL> selectionStrategy = new RoundRobinSelection<CL>(cpMap); 
	
	public ConnectionPoolImpl(ConnectionFactory<CL> cFactory, ConnectionPoolConfiguration cpConfig, ConnectionPoolMonitor cpMon) {
		this.connFactory = cFactory;
		this.cpConfiguration = cpConfig;
		this.cpMonitor = cpMon;
	}
	
	@Override
	public boolean addHost(Host host) {
		
		HostConnectionPool<CL> connPool = cpMap.get(host);
		
		if (connPool != null) {
			if (Logger.isDebugEnabled()) {
				Logger.debug("HostConnectionPool already exists for host: " + host + ", ignoring addHost");
			}
			return false;
		}
		
		HostConnectionPoolImpl<CL> hostPool = new HostConnectionPoolImpl<CL>(host, connFactory, cpConfiguration, cpMonitor, recoveryThreadPool);
		
		HostConnectionPool<CL> prevPool = cpMap.putIfAbsent(host, hostPool);
		if (prevPool == null) {
			// This is the first time we are adding this pool. 
			Logger.info("Adding host conneciton pool for host: " + host);
			
			try {
				hostPool.primeConnections();
				selectionStrategy.addHost(host, hostPool);
				cpMonitor.hostAdded(host, hostPool);
				
				return true;
			} catch (DynoException e) {
				Logger.info("Failed to init host pool for host: " + host, e);
				cpMap.remove(host);
				return false;
			}
		} else {
			return false;
		}
	}

	@Override
	public boolean removeHost(Host host) {
		 
		HostConnectionPool<CL> hostPool = cpMap.remove(host);
		if (hostPool != null) {
			selectionStrategy.removeHost(host, hostPool);

			cpMonitor.hostRemoved(host);
			hostPool.shutdown();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean isHostUp(Host host) {
		HostConnectionPool<CL> hostPool = cpMap.get(host);
		return (hostPool != null) ? hostPool.isActive() : false;
	}

	@Override
	public boolean hasHost(Host host) {
		return cpMap.get(host) != null;
	}

	@Override
	public List<HostConnectionPool<CL>> getActivePools() {
		
		return new ArrayList<HostConnectionPool<CL>>(Collections2.filter(getPools(), new com.google.common.base.Predicate<HostConnectionPool<CL>>() {

			@Override
			public boolean apply(@Nullable HostConnectionPool<CL> hostPool) {
				if (hostPool == null) {
					return false;
				}
				return hostPool.isActive();
			}
		}));
	}

	@Override
	public List<HostConnectionPool<CL>> getPools() {
		return new ArrayList<HostConnectionPool<CL>>(cpMap.values());
	}

	@Override
	public Future<Boolean> updateHosts(Collection<Host> hostsUp, Collection<Host> hostsDown) {
		
		boolean condition = false;
		for (Host hostUp : hostsUp) {
			condition |= addHost(hostUp);
		}
		for (Host hostDown : hostsDown) {
			condition |= removeHost(hostDown);
		}
		return getEmptyFutureTask(condition);
	}

	@Override
	public HostConnectionPool<CL> getHostPool(Host host) {
		return cpMap.get(host);
	}

	@Override
	public <R> OperationResult<R> executeWithFailover(Operation<CL, R> op) throws DynoException {
		
		// Start recording the operation
		long startTime = System.currentTimeMillis();
		
		RetryPolicy retry = cpConfiguration.getRetryPolicyFactory().getRetryPolicy();
		retry.begin();
		
		DynoException lastException = null;
		
		do  {
			Connection<CL> connection = null;
			
			try { 
				connection = 
						selectionStrategy.getConnection(op, cpConfiguration.getMaxTimeoutWhenExhausted(), TimeUnit.MILLISECONDS);
				OperationResult<R> result = connection.execute(op);
				
				retry.success();
				cpMonitor.incOperationSuccess(connection.getHost(), System.currentTimeMillis()-startTime);
				
				return result; 
				
			} catch(NoAvailableHostsException e) {
				cpMonitor.incOperationFailure(null, e);
				throw e;
			} catch(DynoException e) {
				
				retry.failure(e);
				lastException = e;
				
				cpMonitor.incOperationFailure(connection != null ? connection.getHost() : null, e);
				if (retry.allowRetry()) {
					cpMonitor.incFailover(connection.getHost(), e);
				}
				
				// Track the connection health so that the pool can be purged at a later point
				if (connection != null) {
					cpHealthTracker.trackConnectionError(connection.getParentConnectionPool().getHost(), lastException);
				}
				
			} catch(Throwable t) {
				throw new RuntimeException(t);
			} finally {
				if (connection != null) {
					connection.getParentConnectionPool().returnConnection(connection);
				}
			}
			
		} while(retry.allowRetry());
		
		throw lastException;
	}

	@Override
	public void shutdown() {
		for (Host host : cpMap.keySet()) {
			removeHost(host);
		}
		recoveryThreadPool.shutdownNow();
	}

	@Override
	public Future<Boolean> start() throws DynoException {
		for (Host host : cpMap.keySet()) {
			cpMap.get(host).primeConnections();
		}
		return getEmptyFutureTask(true);
	}
	
	private class ConnectionPoolHealthTracker { 
		
		private final ConcurrentHashMap<Host, ErrorRateMonitor> errorRates = new ConcurrentHashMap<Host, ErrorRateMonitor>();
		
		private void trackConnectionError(Host host, DynoException e) {
			
			if (e != null && e instanceof FatalConnectionException) {
				
				ErrorRateMonitor errorMonitor = errorRates.get(host);
				
				if (errorMonitor == null) {
					errorMonitor = ErrorRateMonitorFactory.createErrorMonitor(cpConfiguration);
					errorRates.putIfAbsent(host, errorMonitor);
					errorMonitor = errorRates.get(host);
				}
				
				boolean shouldFail = errorMonitor.trackErrorRate(1);
				
				if (shouldFail) {
					Logger.warn("Dyno removing host conneciton pool for host: " + host + " due to too many errors");
					removeHost(host);
				}
			}
		}
	}

	public static class ErrorRateMonitorFactory {

		public static ErrorRateMonitor createErrorMonitor(ConnectionPoolConfiguration cpConfig) {
			return new ErrorRateMonitor(cpConfig.getErrorCheckConfig());
		}
	}
	
	private Future<Boolean> getEmptyFutureTask(final Boolean condition) {
		
		final Callable<Boolean> task = new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return condition;
			}
		};
		
		try { 
			task.call();
		} catch (Exception e) {
			// do nothing here.
		}
		return new FutureTask<Boolean>(task);
	}

	
	public static class UnitTest {
		
		private static class TestClient {
			
			private final AtomicInteger ops = new AtomicInteger(0);
		}

		private static TestClient client = new TestClient();
		
		private static ConnectionPoolConfigurationImpl cpConfig = new ConnectionPoolConfigurationImpl("TestClient");
		private static CountingConnectionPoolMonitor cpMonitor = new CountingConnectionPoolMonitor();
		
		private static class TestConnection implements Connection<TestClient> {

			private AtomicInteger ops = new AtomicInteger(0);
			private DynoConnectException ex; 
			
			private HostConnectionPool<TestClient> hostPool;
			
			private TestConnection(HostConnectionPool<TestClient> pool) {
				this.hostPool = pool;
			}
			
			@Override
			public <R> OperationResult<R> execute(Operation<TestClient, R> op) throws DynoException {

				try {
					if (op != null) {
						op.execute(client, null);
					}
				} catch (DynoConnectException e) {
					ex = e;
					throw e;
				}
				ops.incrementAndGet();
				return null;
			}

			@Override
			public void close() {
			}

			@Override
			public Host getHost() {
				return hostPool.getHost();
			}

			@Override
			public void open() throws DynoException {
			}

			@Override
			public DynoConnectException getLastException() {
				return ex;
			}

			@Override
			public HostConnectionPool<TestClient> getParentConnectionPool() {
				return hostPool;
			}

			@Override
			public <R> ListenableFuture<OperationResult<R>> executeAsync(AsyncOperation<TestClient, R> op) throws DynoException {
				throw new RuntimeException("Not Implemented");
			}
		}
		
		private static ConnectionFactory<TestClient> connFactory = new ConnectionFactory<TestClient>() {

			@Override
			public Connection<TestClient> createConnection(HostConnectionPool<TestClient> pool, ConnectionObservor cObservor) throws DynoConnectException, ThrottledException {
				return new TestConnection(pool);
			}
		};
		
		private static Host host1 = new Host("host1", 8080);
		private static Host host2 = new Host("host2", 8080);
		private static Host host3 = new Host("host3", 8080);
		
		@Before
		public void beforeTest() {
			
			client = new TestClient();
			cpConfig = new ConnectionPoolConfigurationImpl("TestClient");
			cpMonitor = new CountingConnectionPoolMonitor();
		}
		
		@Test
		public void testConnectionPoolNormal() throws Exception {

			final ConnectionPoolImpl<TestClient> pool = new ConnectionPoolImpl<TestClient>(connFactory, cpConfig, cpMonitor);
			pool.addHost(host1);
			pool.addHost(host2);
			
			final Callable<Void> testLogic = new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					Thread.sleep(1000);
					return null;
				}
			};
			
			runTest(pool, testLogic);
			
			checkConnectionPoolMonitorStats(2);
			
			checkHostStats(host1);
			checkHostStats(host2);
		}
		
		private void checkConnectionPoolMonitorStats(int numHosts)  {
			Assert.assertTrue("Total ops: " + client.ops.get(), client.ops.get() > 0);

			Assert.assertEquals(client.ops.get(), cpMonitor.getOperationSuccessCount());
			Assert.assertEquals(0, cpMonitor.getOperationFailureCount());
			Assert.assertEquals(0, cpMonitor.getOperationTimeoutCount());
			
			Assert.assertEquals(numHosts*3, cpMonitor.getConnectionCreatedCount());
			Assert.assertEquals(0, cpMonitor.getConnectionCreateFailedCount());
			Assert.assertEquals(numHosts*3, cpMonitor.getConnectionClosedCount());
			
			Assert.assertEquals(client.ops.get(), cpMonitor.getConnectionBorrowedCount());
			Assert.assertEquals(client.ops.get(), cpMonitor.getConnectionReturnedCount());
		}
		
		@Test
		public void testAddingNewHosts() throws Exception {
			
			final ConnectionPoolImpl<TestClient> pool = new ConnectionPoolImpl<TestClient>(connFactory, cpConfig, cpMonitor);
			pool.addHost(host1);
			pool.addHost(host2);
			
			final Callable<Void> testLogic = new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					Thread.sleep(1000);
					pool.addHost(host3);
					Thread.sleep(1000);
					return null;
				}
			};
			
			runTest(pool, testLogic);
			
			checkConnectionPoolMonitorStats(3);
			
			checkHostStats(host1);
			checkHostStats(host2);
			checkHostStats(host3);

			HostConnectionStats h1Stats = cpMonitor.getHostStats().get(host1);
			HostConnectionStats h2Stats = cpMonitor.getHostStats().get(host2);
			HostConnectionStats h3Stats = cpMonitor.getHostStats().get(host3);
			
			Assert.assertTrue("h3Stats: " + h3Stats + " h1Stats: " + h1Stats, h1Stats.getOperationSuccessCount() > h3Stats.getOperationSuccessCount());
			Assert.assertTrue("h3Stats: " + h3Stats + " h2Stats: " + h2Stats, h2Stats.getOperationSuccessCount() > h3Stats.getOperationSuccessCount());
		}
		
		private void checkHostStats(Host host) {

			HostConnectionStats hStats = cpMonitor.getHostStats().get(host);
			Assert.assertTrue("host ops: " + hStats.getOperationSuccessCount(), hStats.getOperationSuccessCount() > 0);
			Assert.assertEquals(0, hStats.getOperationErrorCount());
			Assert.assertEquals(3, hStats.getConnectionsCreated());
			Assert.assertEquals(0, hStats.getConnectionsCreateFailed());
			Assert.assertEquals(3, hStats.getConnectionsClosed());
			Assert.assertEquals(hStats.getOperationSuccessCount(), hStats.getConnectionsBorrowed());
			Assert.assertEquals(hStats.getOperationSuccessCount(), hStats.getConnectionsReturned());
		}

		@Test
		public void testRemovingHosts() throws Exception {
			
			final ConnectionPoolImpl<TestClient> pool = new ConnectionPoolImpl<TestClient>(connFactory, cpConfig, cpMonitor);
			pool.addHost(host1);
			pool.addHost(host2);
			pool.addHost(host3);
			
			final Callable<Void> testLogic = new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					Thread.sleep(1000);
					pool.removeHost(host2);
					Thread.sleep(1000);
					return null;
				}
			};
			
			runTest(pool, testLogic);
			
			checkConnectionPoolMonitorStats(3);
			
			checkHostStats(host1);
			checkHostStats(host2);
			checkHostStats(host3);
			
			HostConnectionStats h1Stats = cpMonitor.getHostStats().get(host1);
			HostConnectionStats h2Stats = cpMonitor.getHostStats().get(host2);
			HostConnectionStats h3Stats = cpMonitor.getHostStats().get(host3);
			
			Assert.assertTrue("h1Stats: " + h1Stats + " h2Stats: " + h2Stats, h1Stats.getOperationSuccessCount() > h2Stats.getOperationSuccessCount());
			Assert.assertTrue("h2Stats: " + h2Stats + " h3Stats: " + h3Stats, h3Stats.getOperationSuccessCount() > h2Stats.getOperationSuccessCount());
		}

		@Test (expected=NoAvailableHostsException.class)
		public void testNoAvailableHosts() throws Exception {

			final ConnectionPoolImpl<TestClient> pool = new ConnectionPoolImpl<TestClient>(connFactory, cpConfig, cpMonitor);
			executeTestClientOperation(pool);
		}
		
		@Test
		public void testPoolExhausted() throws Exception {

			final ConnectionPoolImpl<TestClient> pool = new ConnectionPoolImpl<TestClient>(connFactory, cpConfig, cpMonitor);
			pool.addHost(host1); pool.addHost(host2); pool.addHost(host3);   // total of 9 connections
			
			// Now exhaust all 9 connections, so that the 10th one can fail with PoolExhaustedException
			final ExecutorService threadPool = Executors.newFixedThreadPool(9);
			
			final Callable<Void> blockConnectionForSomeTime = new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					try { 
						Thread.sleep(10000);  // sleep for a VERY long time to ensure pool exhaustion
					} catch (InterruptedException e) {
						// just return
					}
					return null;
				}
			};
			
			final CountDownLatch latch = new CountDownLatch(9);
			for (int i=0; i<9; i++) {
				threadPool.submit(new Callable<Void>() {

					@Override
					public Void call() throws Exception {
						latch.countDown();
						executeTestClientOperation(pool, blockConnectionForSomeTime);
						return null;
					}
				});
			}
			
			latch.await(); Thread.sleep(100); // wait patiently for all threads to have blocked the connections
			
			try {
				executeTestClientOperation(pool);
				Assert.fail("TEST FAILED");
			} catch (PoolExhaustedException e) {
				threadPool.shutdownNow();
				pool.shutdown();
			}
		}
		
		
		@Test
		public void testHostEvictionDueToErrorRates() throws Exception {
			
			// First configure the error rate monitor
			ErrorRateMonitorConfigImpl errConfig = (ErrorRateMonitorConfigImpl) cpConfig.getErrorCheckConfig();
			errConfig.checkFrequency = 1;
			errConfig.window = 1;
			errConfig.suppressWindow = 60;
			
			errConfig.addThreshold(10, 1, 100);
					
			final AtomicReference<String> badHost = new AtomicReference<String>();
			
			final ConnectionFactory<TestClient> badConnectionFactory = new ConnectionFactory<TestClient>() {

				@Override
				public Connection<TestClient> createConnection(final HostConnectionPool<TestClient> pool, ConnectionObservor cObservor) throws DynoConnectException, ThrottledException {
					
					return new TestConnection(pool) {

						@Override
						public <R> OperationResult<R> execute(Operation<com.netflix.dyno.connectionpool.impl.ConnectionPoolImpl.UnitTest.TestClient, R> op) throws DynoException {
							if (pool.getHost().getHostName().equals(badHost.get())) {
								throw new FatalConnectionException("Fail for bad host");
							}
							return super.execute(op);
						}
					};
				}
			};
			
			final ConnectionPoolImpl<TestClient> pool = new ConnectionPoolImpl<TestClient>(badConnectionFactory, cpConfig, cpMonitor);
			pool.addHost(host1);
			pool.addHost(host2);
			pool.addHost(host3);

			final Callable<Void> testLogic = new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					Thread.sleep(2000);
					badHost.set("host2");
					Thread.sleep(2000);
					return null;
				}
			};
			
			runTest(pool, testLogic);
			
			Assert.assertTrue("Total ops: " + client.ops.get(), client.ops.get() > 0);
			Assert.assertTrue("Total errors: " + cpMonitor.getOperationFailureCount(), cpMonitor.getOperationFailureCount() > 0);
			
			Assert.assertEquals(9, cpMonitor.getConnectionCreatedCount());
			Assert.assertEquals(0, cpMonitor.getConnectionCreateFailedCount());
			Assert.assertEquals(9, cpMonitor.getConnectionClosedCount());
			
			Assert.assertEquals(client.ops.get() + cpMonitor.getOperationFailureCount(), cpMonitor.getConnectionBorrowedCount());
			Assert.assertEquals(client.ops.get() + cpMonitor.getOperationFailureCount(), cpMonitor.getConnectionReturnedCount());
			
			checkHostStats(host1); 
			checkHostStats(host3);
			
			HostConnectionStats h2Stats = cpMonitor.getHostStats().get(host2);
			Assert.assertEquals(cpMonitor.getOperationFailureCount(), h2Stats.getOperationErrorCount());

		}
		
		@Test
		public void testWithRetries() throws Exception {
			
			final ConnectionFactory<TestClient> badConnectionFactory = new ConnectionFactory<TestClient>() {
				@Override
				public Connection<TestClient> createConnection(final HostConnectionPool<TestClient> pool, ConnectionObservor cObservor) throws DynoConnectException, ThrottledException {
					return new TestConnection(pool) {
						@Override
						public <R> OperationResult<R> execute(Operation<com.netflix.dyno.connectionpool.impl.ConnectionPoolImpl.UnitTest.TestClient, R> op) throws DynoException {
							throw new DynoException("Fail for bad host");
						}
					};
				}
			};
			
			final RetryNTimes retry = new RetryNTimes(3);
			final RetryPolicyFactory rFactory = new RetryPolicyFactory() {
				@Override
				public RetryPolicy getRetryPolicy() {
					return retry;
				}
			};
			
			final ConnectionPoolImpl<TestClient> pool = new ConnectionPoolImpl<TestClient>(badConnectionFactory, cpConfig.setRetryPolicyFactory(rFactory), cpMonitor);
			pool.addHost(host1);
			
			
			try { 
				executeTestClientOperation(pool, null);
				Assert.fail("Test failed: expected PoolExhaustedException");
			} catch (DynoException e) {
				Assert.assertEquals("Retry: " + retry.getAttemptCount(), 3, retry.getAttemptCount());
			}
		}

		private void executeTestClientOperation(final ConnectionPoolImpl<TestClient> pool) {
			executeTestClientOperation(pool, null);
		}		
		
		private void executeTestClientOperation(final ConnectionPoolImpl<TestClient> pool, final Callable<Void> customLogic) {
			pool.executeWithFailover(new Operation<TestClient, Integer>() {

				@Override
				public Integer execute(TestClient client, ConnectionContext state) throws DynoException {
					if (customLogic != null) {
						try {
							customLogic.call();
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
					client.ops.incrementAndGet();
					return 1;
				}

				@Override
				public String getName() {
					return "TestOperation";
				}
				

				@Override
				public String getKey() {
					return "TestOperation";
				}
			});
		}


		private void runTest(final ConnectionPoolImpl<TestClient> pool, final Callable<Void> customTestLogic) throws Exception {
			
			int nThreads = 4;
			final ExecutorService threadPool = Executors.newFixedThreadPool(nThreads);
			final AtomicBoolean stop = new AtomicBoolean(false);

			for (int i=0; i<nThreads; i++) {
				threadPool.submit(new Callable<Void>() {

					@Override
					public Void call() throws Exception {
						try {
						while (!stop.get() && !Thread.currentThread().isInterrupted()) {
							try {
							pool.executeWithFailover(new Operation<TestClient, Integer>() {

								@Override
								public Integer execute(TestClient client, ConnectionContext state) throws DynoException {
									client.ops.incrementAndGet();
									return 1;
								}

								@Override
								public String getName() {
									return "TestOperation";
								}

								@Override
								public String getKey() {
									return "TestOperation";
								}
							});
							} catch (DynoException e) {
								
							}
						}
					
					} finally {
					}
						return null;
					}
					});
			}
			
			customTestLogic.call();
			
			stop.set(true);
			threadPool.shutdownNow();
			pool.shutdown();
		}
	}


	@Override
	public <R> Future<OperationResult<R>> executeAsync(AsyncOperation<CL, R> op) throws DynoException {
		
		DynoException lastException = null;
		Connection<CL> connection = null;
		long startTime = System.currentTimeMillis();
		
		try { 
			connection = 
					selectionStrategy.getConnection(op, cpConfiguration.getMaxTimeoutWhenExhausted(), TimeUnit.MILLISECONDS);
			
			Future<OperationResult<R>> futureResult = connection.executeAsync(op);
			
			cpMonitor.incOperationSuccess(connection.getHost(), System.currentTimeMillis()-startTime);
			
			return futureResult; 
			
		} catch(NoAvailableHostsException e) {
			cpMonitor.incOperationFailure(null, e);
			throw e;
		} catch(DynoException e) {
			
			lastException = e;
			cpMonitor.incOperationFailure(connection != null ? connection.getHost() : null, e);
			
			// Track the connection health so that the pool can be purged at a later point
			if (connection != null) {
				cpHealthTracker.trackConnectionError(connection.getParentConnectionPool().getHost(), lastException);
			}
			
		} catch(Throwable t) {
			t.printStackTrace();
		} finally {
			if (connection != null) {
				connection.getParentConnectionPool().returnConnection(connection);
			}
		}
		return null;
	}
}