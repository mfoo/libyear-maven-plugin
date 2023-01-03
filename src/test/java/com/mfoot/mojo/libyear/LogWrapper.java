/*
 * Copyright 2023 Martin Foot
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

package com.mfoot.mojo.libyear;

import org.apache.maven.plugin.logging.Log;

import java.util.ArrayList;
import java.util.List;

class LogWrapper implements Log {

	// TODO: Implement these
	private final Log wrappedLogger;

	public final List<String> infoLogs = new ArrayList<>();
	public final List<String> errorLogs = new ArrayList<>();
	public final List<String> debugLogs = new ArrayList<>();

	public LogWrapper(Log wrappedLogger) {
		this.wrappedLogger = wrappedLogger;
	}

	@Override
	public boolean isDebugEnabled() {
		return true;
	}

	@Override
	public void debug(CharSequence charSequence) {
		debugLogs.add(charSequence.toString());
	}

	@Override
	public void debug(CharSequence charSequence, Throwable throwable) {

	}

	@Override
	public void debug(Throwable throwable) {

	}

	@Override
	public boolean isInfoEnabled() {
		return false;
	}

	@Override
	public void info(CharSequence charSequence) {
		infoLogs.add(charSequence.toString());
	}

	@Override
	public void info(CharSequence charSequence, Throwable throwable) {

	}

	@Override
	public void info(Throwable throwable) {

	}

	@Override
	public boolean isWarnEnabled() {
		return false;
	}

	@Override
	public void warn(CharSequence charSequence) {

	}

	@Override
	public void warn(CharSequence charSequence, Throwable throwable) {

	}

	@Override
	public void warn(Throwable throwable) {

	}

	@Override
	public boolean isErrorEnabled() {
		return false;
	}

	@Override
	public void error(CharSequence charSequence) {
		errorLogs.add(charSequence.toString());
	}

	@Override
	public void error(CharSequence charSequence, Throwable throwable) {

	}

	@Override
	public void error(Throwable throwable) {

	}
}
