/*
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.bai.model.policy.slurper;

import java.util.Collections;
import java.util.Dictionary;
import java.util.List;

import org.fusesource.bai.EventType;
import org.fusesource.bai.model.policy.Constants.ActionType;
import org.fusesource.bai.model.policy.Constants.FilterElement;
import org.fusesource.bai.model.policy.Constants.FilterMethod;
import org.fusesource.bai.model.policy.Policy;
import org.fusesource.bai.model.policy.PolicySet;
import org.fusesource.bai.model.policy.Scope;

/**
 * This implementation of {@link PolicySlurper} reads a OSGi Config Admin-compatible text property file and constructs the Policy model.
 * @author Raul Kripalani
 *
 */
public class PropertyMapPolicySlurper implements PolicySlurper {

	private Dictionary properties;
	private PolicySet policyCache;
	
	public PropertyMapPolicySlurper() { }
	
	public PropertyMapPolicySlurper(Dictionary properties) {
		this.properties = properties;
	}
	
	@Override
	public synchronized PolicySet slurp() {
		if (policyCache != null) {
			return policyCache;
		}
		
		PolicySet answer = new PolicySet();
		List<Object> keys = Collections.list(properties.keys());
		
		for (Object k : keys) {
			String value = (String) properties.get((String) k);
			String[] splitKey = ((String) k).split("\\.");
			
			String qualifier = splitKey[0];
			Policy result = null;
			if ("camelContext".equals(qualifier)) {
				result = parseCamelContext(splitKey, value);
			}
			
			if ("event".equals(qualifier)) {
				result = parseEvent(splitKey, value);
			}
			
			if ("exchange".equals(qualifier)) {
				result = parseExchange(splitKey, value);
			}
			
			if ("endpoint".equals(qualifier)) {
				result = parseEndpoint(splitKey, value);
			}
			answer.add(result);
		}
		
		policyCache = answer;
		return answer;
	}

	@Override
	public PolicySet refresh() {
		policyCache = slurp();
		return policyCache;
	}
	
	/**
	 * Parse this format: camelContext.(include|exclude)[/$bundleIDRegex] = $camelContextIDRegex.
	 * Later changed to: camelContext.exclude = camelContextPatterns. Where camelContextPatterns is a space-separated 
	 * list of camelContextPattern instances. A camelContextPatterns is of the form bundleSymbolicNamePattern[:camelContextIdPattern].
	 * @param splitKey
	 * @param value
	 * @return
	 */
	private Policy parseCamelContext(String[] splitKey, String value) {
		Policy policy = new Policy();
		Scope scope = new Scope();
		scope.filterElement = FilterElement.CONTEXT;
		scope.filterMethod = FilterMethod.ENUM_VALUE_MULTIPLE;
		
		for (String token : value.split(" ")) {
			scope.enumValues.add(token);
		}
		
		policy.scope.add(scope);
		
		if ("exclude".equals(splitKey[1].toLowerCase())) {
			policy.action.type = ActionType.EXCLUDE;
		} else {
			policy.action.type = ActionType.INCLUDE;
		}
		
		policy.pruneRedundantScopes();
		return policy;
	}
	
	/**
	 * Parse this format: event.exclude.$eventName.$bundleRegex = (include|exclude) $camelContextIdRegex
	 * @param splitKey
	 * @param value
	 * @return
	 */
	private Policy parseEvent(String[] splitKey, String value) {
		Policy policy = new Policy();
		Scope scope = new Scope();
		
		if ("exclude".equals(splitKey[1].toLowerCase())) {
			policy.action.type = ActionType.EXCLUDE;
		} else {
			policy.action.type = ActionType.INCLUDE;
		}
		
		// Scope this policy to the specific event
		scope.filterElement = FilterElement.EVENT;
		scope.filterMethod = FilterMethod.ENUM_VALUE_ONE;
		scope.enumValues.add(toEventType(splitKey[2]).toString());
		policy.scope.add(scope);
		
		// Scope this policy to the Bundle
		scope = new Scope();
		scope.filterElement = FilterElement.BUNDLE;
		scope.filterMethod = FilterMethod.EXPRESSION;
		scope.expressionLanguage = "wildcardAwareString";
		scope.expression = splitKey[3];
		policy.scope.add(scope);
		
		// Scope this policy to the Camel Context
		scope = new Scope();
		scope.filterElement = FilterElement.CONTEXT;
		scope.filterMethod = FilterMethod.EXPRESSION;
		scope.expressionLanguage = "wildcardAwareString";
		scope.expression = value;
		policy.scope.add(scope);
		
		policy.pruneRedundantScopes();
		return policy;

	}
	
	/**
	 * Parse this format: exchange.filter.$eventType.$language[/$bundleIDRegex[/$camelContextIDRegex]] = expression
	 * @param splitKey
	 * @param value
	 * @return
	 */
	private Policy parseExchange(String[] splitKey, String value) {
		Policy policy = new Policy();
		Scope scope = new Scope();
		
		// Scope this policy to the specific event
		scope.filterElement = FilterElement.EVENT;
		scope.filterMethod = FilterMethod.ENUM_VALUE_ONE;
		scope.enumValues.add(toEventType(splitKey[2]).toString());
		policy.scope.add(scope);
		
		// Are there any Bundle IDs or Camel Context IDs?
		String languageBundleContext[] = splitKey[3].split("/");
		
		// Scope this policy to Exchanges matching the specified expression
		scope = new Scope();
		scope.filterElement = FilterElement.EXCHANGE;
		scope.filterMethod = FilterMethod.EXPRESSION;
		scope.expressionLanguage = languageBundleContext[0];
		scope.expression = value;
		policy.scope.add(scope);
		
		// Let's process the Bundle Symbolic Name
		if (languageBundleContext.length >= 2) {
			scope = new Scope();
			scope.filterElement = FilterElement.BUNDLE;
			scope.filterMethod = FilterMethod.EXPRESSION;
			scope.expressionLanguage = "wildcardAwareString";
			scope.expression = languageBundleContext[1];
			policy.scope.add(scope);
		}
		
		// Let's process the Context ID
		if (languageBundleContext.length == 3) {
			scope = new Scope();
			scope.filterElement = FilterElement.CONTEXT;
			scope.filterMethod = FilterMethod.EXPRESSION;
			scope.expressionLanguage = "wildcardAwareString";
			scope.expression = languageBundleContext[2];
			policy.scope.add(scope);
		}
		
		// TODO: according to the model, there's no capability to exclude; so you need to tailor your expression if you want to do that
		policy.action.type = ActionType.INCLUDE;
		policy.pruneRedundantScopes();
		return policy;
	}
	
	/**
	 * Parse this format: endpoint.(include|exclude)[/$bundleIDRegex[/$camelContextIDRegex]] = $endpointUriRegex
	 * TODO: Need to add support for XML namespace definition in XPath expressions, maybe following the Java QName format.
	 * @param splitKey
	 * @param value
	 * @return
	 */
	private Policy parseEndpoint(String[] splitKey, String value) {
		Policy policy = new Policy();
		Scope scope = new Scope();
		
		// Scope this policy to the specific event
		scope.filterElement = FilterElement.ENDPOINT;
		scope.filterMethod = FilterMethod.EXPRESSION;
		// TODO: Is this wildcard aware or regex?
		scope.expressionLanguage = "wildcardAwareString";
		scope.expression = value;
		policy.scope.add(scope);
		
		// Are there any Bundle IDs or Camel Context IDs?
		String remainingKey[] = splitKey[1].split("/");
		
		if ("exclude".equals(remainingKey[0].toLowerCase())) {
			policy.action.type = ActionType.EXCLUDE;
		} else {
			policy.action.type = ActionType.INCLUDE;
		}
		
		// Let's process the Bundle Symbolic Name
		if (remainingKey.length >= 2) {
			scope = new Scope();
			scope.filterElement = FilterElement.BUNDLE;
			scope.filterMethod = FilterMethod.EXPRESSION;
			scope.expressionLanguage = "wildcardAwareString";
			scope.expression = remainingKey[1];
			policy.scope.add(scope);
		}
		
		// Let's process the Context ID
		if (remainingKey.length == 3) {
			scope = new Scope();
			scope.filterElement = FilterElement.CONTEXT;
			scope.filterMethod = FilterMethod.EXPRESSION;
			scope.expressionLanguage = "wildcardAwareString";
			scope.expression = remainingKey[2];
			policy.scope.add(scope);
		}
		
		policy.pruneRedundantScopes();
		return policy;
	}
	
	private EventType toEventType(String event) {
		return EventType.valueOf(event.toUpperCase());
	}
	
	public Dictionary getProperties() {
		return properties;
	}

	public void setProperties(Dictionary properties) {
		this.properties = properties;
	}

	public PolicySet getPolicies() {
		return policyCache;
	}
	
}
