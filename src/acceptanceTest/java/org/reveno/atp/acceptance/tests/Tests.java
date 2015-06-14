/** 
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.reveno.atp.acceptance.tests;

import java.io.File;
import java.util.Arrays;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;
import org.reveno.atp.acceptance.api.commands.CreateNewAccountCommand;
import org.reveno.atp.acceptance.api.commands.NewOrderCommand;
import org.reveno.atp.acceptance.api.events.AccountCreatedEvent;
import org.reveno.atp.acceptance.api.events.OrderCreatedEvent;
import org.reveno.atp.acceptance.model.Order.OrderType;
import org.reveno.atp.acceptance.views.AccountView;
import org.reveno.atp.acceptance.views.OrderView;
import org.reveno.atp.api.Reveno;

public class Tests extends RevenoBaseTest {
	
	@Test 
	public void testBasic() throws Exception {
		Reveno reveno = createEngine();
		reveno.startup();
		
		Waiter accountCreatedEvent = listenFor(reveno, AccountCreatedEvent.class);
		Waiter orderCreatedEvent = listenFor(reveno, OrderCreatedEvent.class);
		long accountId = sendCommandSync(reveno, new CreateNewAccountCommand("USD", 1000_000L));
		AccountView accountView = reveno.query().find(AccountView.class, accountId).get();
		
		Assert.assertTrue(accountCreatedEvent.isArrived());
		Assert.assertEquals(accountId, accountView.accountId);
		Assert.assertEquals("USD", accountView.currency);
		Assert.assertEquals(1000_000L, accountView.balance);
		Assert.assertEquals(0, accountView.orders().size());
		
		long orderId = sendCommandSync(reveno, new NewOrderCommand(accountId, Optional.empty(), "EUR/USD", 134000, 1000, OrderType.MARKET));
		OrderView orderView = reveno.query().find(OrderView.class, orderId).get();
		accountView = reveno.query().find(AccountView.class, accountId).get();
		
		Assert.assertTrue(orderCreatedEvent.isArrived());
		Assert.assertEquals(orderId, orderView.id);
		Assert.assertEquals(1, accountView.orders().size());
		
		reveno.shutdown();
	}
	
	@Test
	public void testAsyncHandlers() throws Exception {
		Reveno reveno = createEngine();
		reveno.startup();
		
		Waiter accountCreatedEvent = listenAsyncFor(reveno, AccountCreatedEvent.class, 1_000);
		sendCommandsBatch(reveno, new CreateNewAccountCommand("USD", 1000_000L), 1_000);
		Assert.assertTrue(accountCreatedEvent.isArrived());
		
		reveno.shutdown();
	}
	
	@Test
	public void testExceptionalEventHandler() throws Exception {
		Reveno reveno = createEngine();
		reveno.startup();
		
		Waiter w = listenFor(reveno, AccountCreatedEvent.class, 1_000, (c) -> {
			if (c == 500 || c == 600 || c == 601) {
				throw new RuntimeException();
			}
		});
		sendCommandsBatch(reveno, new CreateNewAccountCommand("USD", 1000_000L), 1_000);
		// it's just fine since on exception we still processing
		// but such events won't be committed
		Assert.assertTrue(w.isArrived());
		
		reveno.shutdown();
		
		reveno = createEngine();
		w = listenFor(reveno, AccountCreatedEvent.class, 4);
		reveno.startup();
		
		// after restart we expect that there will be 3 replayed
		// events - the count of exceptions
		Assert.assertFalse(w.isArrived());
		Assert.assertEquals(1, w.getCount());
		
		reveno.shutdown();
	}
	
	@Test
	public void testExceptionalAsyncEventHandler() throws Exception {
		Reveno reveno = createEngine();
		reveno.events().asyncEventExecutors(10);
		reveno.startup();
		
		Waiter w = listenAsyncFor(reveno, AccountCreatedEvent.class, 1_000, (c) -> {
			if (c == 500 || c == 600 || c == 601) {
				throw new RuntimeException();
			}
		});
		sendCommandsBatch(reveno, new CreateNewAccountCommand("USD", 1000_000L), 1_000);
		Assert.assertTrue(w.isArrived());
		
		reveno.shutdown();
		
		reveno = createEngine();
		w = listenFor(reveno, AccountCreatedEvent.class, 4);
		reveno.startup();
		
		Assert.assertFalse(w.isArrived());
		Assert.assertEquals(1, w.getCount());
		
		reveno.shutdown();
	}
	
	@Test
	public void testBatch() throws Exception {
		Reveno reveno = createEngine();
		Waiter accountsWaiter = listenFor(reveno, AccountCreatedEvent.class, 10_000);
		Waiter ordersWaiter = listenFor(reveno, OrderCreatedEvent.class, 10_000);
		reveno.startup();
		
		generateAndSendCommands(reveno, 10_000);
		
		Assert.assertEquals(10_000, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(10_000, reveno.query().select(OrderView.class).size());
		
		Assert.assertTrue(accountsWaiter.isArrived());
		Assert.assertTrue(ordersWaiter.isArrived());
		
		reveno.shutdown();
	}
	
	@Test
	public void testReplay() throws Exception {
		testBasic();
		
		Reveno reveno = createEngine();
		Waiter accountCreatedEvent = listenFor(reveno, AccountCreatedEvent.class);
		Waiter orderCreatedEvent = listenFor(reveno, OrderCreatedEvent.class);
		reveno.startup();
		
		Assert.assertFalse(accountCreatedEvent.isArrived());
		Assert.assertFalse(orderCreatedEvent.isArrived());
		
		Assert.assertEquals(1, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(1, reveno.query().select(OrderView.class).size());
		
		reveno.shutdown();
	}
	
	@Test
	public void testBatchReplay() throws Exception {
		testBatch();
		
		Reveno reveno = createEngine();
		Waiter accountsWaiter = listenFor(reveno, AccountCreatedEvent.class, 1);
		Waiter ordersWaiter = listenFor(reveno, OrderCreatedEvent.class, 1);
		reveno.startup();
		
		Assert.assertEquals(10_000, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(10_000, reveno.query().select(OrderView.class).size());
		
		Assert.assertFalse(accountsWaiter.isArrived());
		Assert.assertFalse(ordersWaiter.isArrived());
		
		long accountId = sendCommandSync(reveno, new CreateNewAccountCommand("USD", 1000_000L));
		Assert.assertEquals(10_001, accountId);
		long orderId = sendCommandSync(reveno, new NewOrderCommand(accountId, Optional.empty(), "EUR/USD", 134000, 1000, OrderType.MARKET));
		Assert.assertEquals(10_001, orderId);
		
		reveno.shutdown();
	}
	
	@Test
	public void testShutdownSnapshooting() throws Exception {
		Reveno reveno = createEngine();
		reveno.config().snapshooting().snapshootAtShutdown(true);
		reveno.startup();
		
		generateAndSendCommands(reveno, 10_000);
		
		Assert.assertEquals(10_000, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(10_000, reveno.query().select(OrderView.class).size());
		
		reveno.shutdown();
		
		Arrays.asList(tempDir.listFiles((dir, name) -> !(name.startsWith("snp")))).forEach(File::delete);
		
		reveno = createEngine();
		reveno.startup();
		
		Assert.assertEquals(10_000, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(10_000, reveno.query().select(OrderView.class).size());
		
		reveno.shutdown();
	}
	
	@Test
	public void testSnapshootingEvery() throws Exception {
		Reveno reveno = createEngine();
		reveno.config().snapshooting().snapshootAtShutdown(false);
		reveno.config().snapshooting().snapshootEvery(1002);
		reveno.startup();
		
		generateAndSendCommands(reveno, 10_005);
		
		Assert.assertEquals(10_005, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(10_005, reveno.query().select(OrderView.class).size());
		
		reveno.shutdown();
		
		Assert.assertEquals(19, tempDir.listFiles((dir, name) -> name.startsWith("snp")).length);
		
		reveno = createEngine();
		reveno.startup();
		
		Assert.assertEquals(10_005, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(10_005, reveno.query().select(OrderView.class).size());
		
		generateAndSendCommands(reveno, 3);
		
		reveno.shutdown();
		
		Arrays.asList(tempDir.listFiles((dir, name) -> name.startsWith("snp"))).forEach(File::delete);
		
		reveno = createEngine();
		Waiter accountCreatedEvent = listenFor(reveno, AccountCreatedEvent.class);
		Waiter orderCreatedEvent = listenFor(reveno, OrderCreatedEvent.class);
		reveno.startup();
		
		Assert.assertEquals(10_008, reveno.query().select(AccountView.class).size());
		Assert.assertEquals(10_008, reveno.query().select(OrderView.class).size());
		Assert.assertFalse(accountCreatedEvent.isArrived());
		Assert.assertFalse(orderCreatedEvent.isArrived());
		
		reveno.shutdown();
	}
	
}