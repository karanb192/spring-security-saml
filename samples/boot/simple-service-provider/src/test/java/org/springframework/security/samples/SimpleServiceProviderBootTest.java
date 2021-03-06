/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.springframework.security.samples;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.saml.SamlTransformer;
import org.springframework.security.saml.helper.SamlTestObjectHelper;
import org.springframework.security.saml.key.SimpleKey;
import org.springframework.security.saml.provider.SamlServerConfiguration;
import org.springframework.security.saml.provider.provisioning.SamlProviderProvisioning;
import org.springframework.security.saml.provider.service.ServiceProviderService;
import org.springframework.security.saml.provider.service.config.LocalServiceProviderConfiguration;
import org.springframework.security.saml.saml2.authentication.Assertion;
import org.springframework.security.saml.saml2.authentication.AuthenticationRequest;
import org.springframework.security.saml.saml2.authentication.LogoutRequest;
import org.springframework.security.saml.saml2.authentication.LogoutResponse;
import org.springframework.security.saml.saml2.authentication.Response;
import org.springframework.security.saml.saml2.authentication.StatusCode;
import org.springframework.security.saml.saml2.metadata.Endpoint;
import org.springframework.security.saml.saml2.metadata.IdentityProviderMetadata;
import org.springframework.security.saml.saml2.metadata.Metadata;
import org.springframework.security.saml.saml2.metadata.NameId;
import org.springframework.security.saml.saml2.metadata.ServiceProviderMetadata;
import org.springframework.security.saml.spi.DefaultSamlAuthentication;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sample.config.AppConfig;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
public class SimpleServiceProviderBootTest {

	@Autowired
	Clock samlTime;

	@Autowired
	SamlServerConfiguration configuration;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SamlTransformer transformer;

	@Autowired
	private SamlProviderProvisioning<ServiceProviderService> provisioning;

	private String idpEntityId;

	private String spBaseUrl;

	@Autowired
	private AppConfig config;

	private MockHttpServletRequest defaultRequest;
	private SamlTestObjectHelper helper;


	@BeforeEach
	void setUp() {
		idpEntityId = "http://simplesaml-for-spring-saml.cfapps.io/saml2/idp/metadata.php";
		spBaseUrl = "http://localhost";
		defaultRequest = new MockHttpServletRequest("GET", spBaseUrl);
		helper = new SamlTestObjectHelper(samlTime);
	}

	@AfterEach
	public void reset() {
		config.getServiceProvider().setSingleLogoutEnabled(true);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@ComponentScan(basePackages = "sample")
	public static class SpringBootApplicationTestConfig {
	}

	@Test
	public void checkConfig() {
		assertNotNull(configuration);
		assertNull(configuration.getIdentityProvider());
		LocalServiceProviderConfiguration sp = configuration.getServiceProvider();
		assertNotNull(sp);
		assertThat(sp.getEntityId(), equalTo("spring.security.saml.sp.id"));
		assertTrue(sp.isSignMetadata());
		assertTrue(sp.isSignRequests());
		SimpleKey activeKey = sp.getKeys().getActive();
		assertNotNull(activeKey);
		List<SimpleKey> standByKeys = sp.getKeys().getStandBy();
		assertNotNull(standByKeys);
		assertThat(standByKeys.size(), equalTo(2));
	}

	@Test
	public void testServiceProviderMetadata() throws Exception {
		ServiceProviderMetadata spm = getServiceProviderMetadata();
		assertThat(spm.getServiceProvider().getSingleLogoutService().isEmpty(), equalTo(false));
		//this gets created automatically when deserializing
		assertThat(spm.getEntityAlias(), equalTo("spring.security.saml.sp.id"));
		for (Endpoint ep : spm.getServiceProvider().getAssertionConsumerService()) {
			assertThat(ep.getLocation(), equalTo("http://localhost:80/saml/sp/SSO/alias/boot-sample-sp"));
		}
		assertThat(
			spm.getServiceProvider().getNameIds(),
			containsInAnyOrder(NameId.UNSPECIFIED, NameId.PERSISTENT, NameId.EMAIL)
		);
	}

	@Test
	public void singleLogoutDisabledMetadata() throws Exception {
		config.getServiceProvider().setSingleLogoutEnabled(false);
		ServiceProviderMetadata spm = getServiceProviderMetadata();
		assertThat(spm.getServiceProvider().getSingleLogoutService(), containsInAnyOrder());
	}


	@Test
	public void authnRequest() throws Exception {
		AuthenticationRequest authn = getAuthenticationRequest();
		assertNotNull(authn);
	}

	@Test
	public void processResponse() throws Exception {
		ServiceProviderService provider = provisioning.getHostedProvider(defaultRequest);
		configuration.getServiceProvider().setWantAssertionsSigned(false);
		String idpEntityId = "http://simplesaml-for-spring-saml.cfapps.io/saml2/idp/metadata.php";
		AuthenticationRequest authn = getAuthenticationRequest();
		IdentityProviderMetadata idp = provider.getRemoteProvider(idpEntityId);
		ServiceProviderMetadata sp = provider.getMetadata();
		Assertion assertion = helper.assertion(sp, idp, authn, "test-user@test.com", NameId.PERSISTENT);
		Response response = helper.response(
			authn,
			assertion,
			sp,
			idp
		);

		String encoded = transformer.samlEncode(transformer.toXml(response), false);
		mockMvc.perform(
			post("/saml/sp/SSO/alias/boot-sample-sp")
				.param("SAMLResponse", encoded)
		)
			.andExpect(status().isFound())
			.andExpect(authenticated());
	}

	@Test
	public void invalidResponse() throws Exception {
		configuration.getServiceProvider().setWantAssertionsSigned(false);
		ServiceProviderService provider = provisioning.getHostedProvider(defaultRequest);
		String idpEntityId = "http://simplesaml-for-spring-saml.cfapps.io/saml2/idp/metadata.php";
		AuthenticationRequest authn = getAuthenticationRequest();
		IdentityProviderMetadata idp = provider.getRemoteProvider(idpEntityId);
		ServiceProviderMetadata sp = provider.getMetadata();
		Assertion assertion = helper.assertion(sp, idp, authn, "test-user@test.com", NameId.PERSISTENT);
		Response response = helper.response(
			authn,
			assertion,
			sp,
			idp
		);
		response.setDestination("invalid SP");

		String encoded = transformer.samlEncode(transformer.toXml(response), false);
		mockMvc.perform(
			post("/saml/sp/SSO/alias/boot-sample-sp")
				.param("SAMLResponse", encoded)
		)
			.andExpect(status().isBadRequest())
			.andExpect(content().string(containsString("Destination mismatch: invalid SP")));
	}

	@Test
	public void initiateLogout() throws Exception {
		ServiceProviderService provider = provisioning.getHostedProvider(defaultRequest);
		AuthenticationRequest authn = getAuthenticationRequest();
		IdentityProviderMetadata idp = provider.getRemoteProvider(idpEntityId);
		ServiceProviderMetadata sp = provider.getMetadata();
		Assertion assertion = helper.assertion(sp, idp, authn, "test-user@test.com", NameId.PERSISTENT);
		DefaultSamlAuthentication authentication = new DefaultSamlAuthentication(
			true,
			assertion,
			idpEntityId,
			sp.getEntityId(),
			null
		);

		String redirect = mockMvc.perform(
			get(sp.getServiceProvider().getSingleLogoutService().get(0).getLocation())
				.with(authentication(authentication))
		)
			.andExpect(status().isFound())
			.andReturn()
			.getResponse()
			.getHeader("Location");

		Map<String, String> params = queryParams(new URI(redirect));
		String request = params.get("SAMLRequest");
		assertNotNull(request);
		LogoutRequest lr = (LogoutRequest) transformer.fromXml(
			transformer.samlDecode(request, true),
			null,
			null
		);
		assertNotNull(lr);
	}

	@Test
	public void receiveLogoutRequest() throws Exception {
		ServiceProviderService provider = provisioning.getHostedProvider(defaultRequest);
		AuthenticationRequest authn = getAuthenticationRequest();
		IdentityProviderMetadata idp = provider.getRemoteProvider(idpEntityId);
		ServiceProviderMetadata sp = provider.getMetadata();
		Assertion assertion = helper.assertion(sp, idp, authn, "test-user@test.com", NameId.PERSISTENT);
		DefaultSamlAuthentication authentication = new DefaultSamlAuthentication(
			true,
			assertion,
			idpEntityId,
			sp.getEntityId(),
			null
		);
		LogoutRequest request = helper.logoutRequest(
			sp,
			idp,
			assertion.getSubject().getPrincipal()
		);

		String xml = transformer.toXml(request);
		String param = transformer.samlEncode(xml, true);

		String redirect = mockMvc.perform(
			get(sp.getServiceProvider().getSingleLogoutService().get(0).getLocation())
				.param("SAMLRequest", param)
				.with(authentication(authentication))
		)
			.andExpect(status().isFound())
			.andExpect(unauthenticated())
			.andReturn()
			.getResponse()
			.getHeader("Location");

		Map<String, String> params = queryParams(new URI(redirect));
		String response = params.get("SAMLResponse");
		assertNotNull(response);
		LogoutResponse lr = (LogoutResponse) transformer.fromXml(
			transformer.samlDecode(response, true),
			null,
			null
		);
		assertNotNull(lr);
		assertThat(lr.getStatus().getCode(), equalTo(StatusCode.SUCCESS));

	}

	@Test
	public void receiveLogoutResponse() throws Exception {
		ServiceProviderService provider = provisioning.getHostedProvider(defaultRequest);
		AuthenticationRequest authn = getAuthenticationRequest();
		IdentityProviderMetadata idp = provider.getRemoteProvider(idpEntityId);
		ServiceProviderMetadata sp = provider.getMetadata();
		Assertion assertion = helper.assertion(sp, idp, authn, "test-user@test.com", NameId.PERSISTENT);
		DefaultSamlAuthentication authentication = new DefaultSamlAuthentication(
			true,
			assertion,
			idpEntityId,
			sp.getEntityId(),
			null
		);
		LogoutRequest request = helper.logoutRequest(
			idp,
			sp,
			assertion.getSubject().getPrincipal()
		);

		LogoutResponse response = helper.logoutResponse(request, sp, idp);

		String xml = transformer.toXml(response);
		String param = transformer.samlEncode(xml, true);

		String redirect = mockMvc.perform(
			get(sp.getServiceProvider().getSingleLogoutService().get(0).getLocation())
				.param("SAMLResponse", param)
				.with(authentication(authentication))
		)
			.andExpect(status().isFound())
			.andExpect(unauthenticated())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		assertEquals(redirect, "/");
	}

	protected AuthenticationRequest getAuthenticationRequest() throws Exception {
		String idpEntityId = "http://simplesaml-for-spring-saml.cfapps.io/saml2/idp/metadata.php";
		String redirect = mockMvc.perform(
			get("/saml/sp/discovery/alias/"+configuration.getServiceProvider().getAlias())
				.param("idp", idpEntityId)
		)
			.andExpect(status().isFound())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		assertNotNull(redirect);
		Map<String, String> params = queryParams(new URI(redirect));
		assertNotNull(params);
		assertFalse(params.isEmpty());
		String request = params.get("SAMLRequest");
		assertNotNull(request);
		String xml = transformer.samlDecode(request, true);
		return (AuthenticationRequest) transformer.fromXml(xml, null, null);
	}

	@Test
	public void selectIdentityProvider() throws Exception {
		mockMvc.perform(
			get("/saml/sp/select")
			.accept(MediaType.TEXT_HTML)
		)
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("<h1>Select an Identity Provider</h1>")))
			.andExpect(content().string(containsString("Simple SAML PHP IDP")))
			.andReturn();
	}


	public static Map<String, String> queryParams(URI url) {
		Map<String, String> queryPairs = new LinkedHashMap<>();
		String query = url.getQuery();
		String[] pairs = query.split("&");
		for (String pair : pairs) {
			int idx = pair.indexOf("=");
			queryPairs.put(
				UriUtils.decode(pair.substring(0, idx), UTF_8.name()),
				UriUtils.decode(pair.substring(idx + 1), UTF_8.name())
			);
		}
		return queryPairs;
	}

	protected ServiceProviderMetadata getServiceProviderMetadata() throws Exception {
		String xml = mockMvc.perform(get("/saml/sp/metadata"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertNotNull(xml);
		Metadata m = (Metadata) transformer.fromXml(xml, null, null);
		assertNotNull(m);
		assertThat(m.getClass(), equalTo(ServiceProviderMetadata.class));
		return (ServiceProviderMetadata)m;
	}
}
