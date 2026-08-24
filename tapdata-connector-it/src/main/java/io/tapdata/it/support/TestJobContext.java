package io.tapdata.it.support;

import io.tapdata.async.master.JobContext;
import io.tapdata.entity.annotations.Implementation;
import io.tapdata.entity.error.CoreException;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

/**
 * 测试环境 JobContext 实现（同步执行语义）。
 * <p>
 * 分区读取（DatabaseReadPartitionSplitter）内部通过 {@link JobContext#create()} 创建
 * 拆分任务上下文，该工厂由 TapRuntime 扫描 {@code io.tapdata} 包下的
 * {@code @Implementation} 注解类提供实现。产品环境中由 async-tools-module 提供，
 * 测试 classpath 不包含该模块，故在此提供等价实现保证 getReadPartitions 等用例可运行。
 * <p>
 * 与产品实现（JobContextImpl）行为一致：所有 foreach/runOnce 同步执行，
 * 任务被 stop 后抛 CoreException 中断。
 */
@Implementation(JobContext.class)
public class TestJobContext extends JobContext {

	@Override
	public void foreach(int start, int maxCount, Function<Integer, Boolean> function) {
		if (function == null) {
			return;
		}
		if (start < 0 || start > maxCount) {
			throw new CoreException(9000, "start {} or maxCount {} illegal", start, maxCount);
		}
		for (int i = start; i < maxCount; i++) {
			checkJobStoppedOrNot();
			Boolean result = function.apply(i);
			checkJobStoppedOrNot();
			if (result != null && !result) {
				break;
			}
		}
	}

	@Override
	public <T> void foreach(Iterator<T> iterator, Function<T, Boolean> function) {
		if (iterator == null || function == null) {
			return;
		}
		while (iterator.hasNext()) {
			checkJobStoppedOrNot();
			Boolean result = function.apply(iterator.next());
			checkJobStoppedOrNot();
			if (result != null && !result) {
				break;
			}
		}
	}

	@Override
	public void foreach(int maxCount, Function<Integer, Boolean> function) {
		foreach(0, maxCount, function);
	}

	@Override
	public <T> void foreach(Collection<T> collection, Function<T, Boolean> function) {
		if (collection == null || function == null) {
			return;
		}
		for (T t : collection) {
			checkJobStoppedOrNot();
			Boolean result = function.apply(t);
			checkJobStoppedOrNot();
			if (result != null && !result) {
				break;
			}
		}
	}

	@Override
	public void checkJobStoppedOrNot() {
		if (stopped.get()) {
			throw new CoreException(9000, "Async job {} stopped, reason {}", id, stopReason);
		}
	}

	@Override
	public <K, V> void foreach(Map<K, V> map, Function<Map.Entry<K, V>, Boolean> entryFunction) {
		if (map == null || entryFunction == null) {
			return;
		}
		for (Map.Entry<K, V> entry : map.entrySet()) {
			checkJobStoppedOrNot();
			Boolean result = entryFunction.apply(entry);
			checkJobStoppedOrNot();
			if (result != null && !result) {
				break;
			}
		}
	}

	@Override
	public void runOnce(Runnable runnable) {
		if (runnable == null) {
			return;
		}
		checkJobStoppedOrNot();
		runnable.run();
		checkJobStoppedOrNot();
	}
}
