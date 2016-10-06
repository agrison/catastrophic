package foo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import foo.ws.GetRandomCatRequest;
import foo.ws.GetRandomCatResponse;
import foo.ws.WsCat;
import lombok.*;
import lombok.experimental.Accessors;
import org.apache.commons.collections.IteratorUtils;
import org.apache.commons.io.IOUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
import org.springframework.data.keyvalue.core.KeyValueTemplate;
import org.springframework.data.keyvalue.repository.KeyValueRepository;
import org.springframework.data.keyvalue.repository.support.SimpleKeyValueRepository;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.RedisKeyValueAdapter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.index.Indexed;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.repository.core.support.ReflectionEntityInformation;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurerAdapter;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;
import redis.embedded.RedisServer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * @author Alexandre Grison - @algrison
 */
@Getter    // generate getters
@Setter    // generate setters
@Aspect    // we are an aspect
@ToString  // generate toString()
@EnableWs  // SOAP is so enterprisy, we definitely need it
@Endpoint  // Seriously, just read above
@EnableWebMvc   // we want MVC
@EnableCaching  // and we want to cache stuff
@Configuration  // this class can configure itself
@RestController // we want some REST
@XmlRootElement // this component is marshallable
@EnableWebSocket // we want web socket, it's so new-generation
@RedisHash("cat")  // this class is an entity saved in redis
@EnableScheduling  // we want scheduled tasks
@EnableWebSecurity  // and some built-in security
@NoArgsConstructor  // generate no args constructor
@ContextConfiguration  // we want context configuration for unit testing
@SpringBootApplication // this is a Spring Boot application
@Accessors(chain = true) // getters/setters are chained (ala jQuery)
@EnableAspectJAutoProxy  // we want AspectJ auto proxy
@EnableAutoConfiguration  // and auto configuration
@EnableRedisRepositories  // since it is an entity we want to enable spring data repositories for redis
@EnableWebSocketMessageBroker // we want a broker for web socket messages
@ComponentScan(basePackages = "foo") // we may scan for additional components in package "foo"
@EqualsAndHashCode(callSuper = false) // generate equals() and hashCode()
@Scope(proxyMode = ScopedProxyMode.NO) // Nope
@RunWith(SpringJUnit4ClassRunner.class) // we are also a unit test, but we need specific bootstrapping for Spring
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // the Spring context could get dirty please clean up
public class Cat extends AbstractWebSocketMessageBrokerConfigurer {
	// ---------- model stuff ----------
	@Id
	private String id;
	@Indexed
	private String url;

	@Autowired
	@JsonIgnore
	KeyValueRepository<Cat, String> repository;

	// ---------- redis ----------
	@Configuration
	@EnableCaching
	public static class Redis extends CachingConfigurerSupport {
		@Bean(initMethod = "start", destroyMethod = "stop")
		public RedisServer redisServer() throws IOException {
			return new RedisServer();
		}
		@Bean
		public RedisConnectionFactory connectionFactory() {
			return new JedisConnectionFactory();
		}
		@Bean
		public RedisTemplate<?, ?> redisTemplate() {
			RedisTemplate<byte[], byte[]> tpl = new RedisTemplate<>();
			tpl.setConnectionFactory(connectionFactory());
			return tpl;
		}
		@Bean
		public KeyValueRepository<Cat, String> crazyRepository() {
			// this is some ugly stuff to create some kind of Spring Data repository with no interface
			return new SimpleKeyValueRepository<>(new ReflectionEntityInformation<Cat, String>(Cat.class), //
														 new KeyValueTemplate(new RedisKeyValueAdapter(redisTemplate())));
		}
		@Bean
		public CacheManager cacheManager(RedisTemplate redisTemplate) {
			RedisCacheManager cacheManager = new RedisCacheManager(redisTemplate);
			cacheManager.setDefaultExpiration(300);
			return cacheManager;
		}
	}

	// ---------- security ----------
	@Configuration
	@EnableWebSecurity
	public static class Security extends WebSecurityConfigurerAdapter {
		@Override
		protected void configure(HttpSecurity http) throws Exception {
			http.csrf().ignoringAntMatchers("/ws");
			http.authorizeRequests().antMatchers("/ws").anonymous();
			http.authorizeRequests().antMatchers("/*").authenticated().and().formLogin();
		}
		@Override
		public void configure(AuthenticationManagerBuilder auth) throws Exception {
			auth.inMemoryAuthentication().withUser("user").password("password").roles("USER");
		}
	}

	// ---------- SOAP ----------
	@EnableWs
	@Configuration
	public static class Soap extends WsConfigurerAdapter {
		@Bean
		public ServletRegistrationBean messageDispatcherServlet(ApplicationContext applicationContext) {
			MessageDispatcherServlet servlet = new MessageDispatcherServlet();
			servlet.setApplicationContext(applicationContext);
			servlet.setTransformWsdlLocations(true);
			return new ServletRegistrationBean(servlet, "/ws/*");
		}
		@Bean(name = "cats")
		public DefaultWsdl11Definition defaultWsdl11Definition(XsdSchema schema) {
			DefaultWsdl11Definition wsdl11Definition = new DefaultWsdl11Definition();
			wsdl11Definition.setPortTypeName("CatThingPort");
			wsdl11Definition.setLocationUri("/ws");
			wsdl11Definition.setTargetNamespace("http://ws.foo");
			wsdl11Definition.setSchema(schema);
			return wsdl11Definition;
		}
		@Bean
		public XsdSchema schema() {
			return new SimpleXsdSchema(new ClassPathResource("cat.xsd"));
		}
	}

	// ---------- SOAP endpoint ----------
	@ResponsePayload
	@PayloadRoot(namespace = "http://ws.foo", localPart = "getRandomCatRequest")
	public GetRandomCatResponse getRandomCat(@RequestPayload GetRandomCatRequest request) {
		GetRandomCatResponse response = new GetRandomCatResponse();
		List<Cat> cats = IteratorUtils.toList(listCats().iterator());
		WsCat cat = new WsCat();
		Cat random = cats.get(new Random().nextInt(cats.size()));
		BeanUtils.copyProperties(random, cat);
		response.setCat(cat);
		return response;
	}

	// ---------- AOP ----------
	@Around("execution(* org.springframework.data.repository.CrudRepository.*(..))")
	public void aroundRepository(ProceedingJoinPoint pjp) {
		System.out.println("Someone's calling a repository " + //
				pjp.getSignature().getName() + "(" + Arrays.toString(pjp.getArgs()) + ")");
	}

	// ---------- internal stuff ----------
	@Cacheable("cats")
	public Iterable<Cat> listCats() {
		return repository.findAll();
	}

	@CacheEvict("cats")
	public Cat addCat(String url) {
		Cat cat = new Cat().setId(UUID.randomUUID().toString()).setUrl(url);
		return repository.save(cat);
	}

	// ---------- scheduling ----------
	@Scheduled(fixedRate = 30000)
	public void generateSomeCat() throws IOException {
		Map<String, String> map = new HashMap<String, String>();
		map = new Gson().fromJson(IOUtils.toString( //
				new URL("http://random.cat/meow").openStream()), map.getClass());
		addCat(map.get("file"));
	}

	// ---------- MVC ----------
	@RequestMapping("/")
	public String homeView() {
		return view("Listing cats", //
			"<table class=table><thead><tr><th>ID<th>Cat<tbody>" + //
				StreamSupport.stream(listCats().spliterator(), false) //
						.map(c -> "<tr><td>" + c.id + "<td><img src=\"" + c.url + "\" width=\"350\"/>") //
						.collect(Collectors.joining()) + "</table>", true);
	}

	@RequestMapping("/new")
	public String newCatView(HttpServletRequest request) {
		CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
		return view("Add a cat", //
						"<form method=post name=cat action=\"/new\">" + //
						"<label for=url>URL:</label><input id=url name=url class=\"form-control\"/>" + //
						"<input type=submit class=\"btn btn-info\"/> <input type=hidden name=\"" + csrfToken.getParameterName() + "\" value=\"" + csrfToken.getToken() + "\"/>",
						false);
	}

	@RequestMapping(value = "/new", method = RequestMethod.POST)
	public String newCat(@ModelAttribute("cat") Cat cat) {
		addCat(cat.url);
		return homeView();
	}

	// ---------- REST ----------
	@RequestMapping(value = "/api/cats", method = RequestMethod.GET)
	public Iterable<Cat> restList() {
		return listCats();
	}

	@RequestMapping(value = "/api/cats/{id}", method = RequestMethod.GET)
	public Cat restFind(@PathVariable String id) {
		return repository.findOne(id);
	}

	@RequestMapping(value = "/api/cats", method = RequestMethod.POST)
	public Cat restAdd(@RequestBody Cat cat) {
		return addCat(cat.getUrl());
	}

	// ---------- HTML view ----------
	public String view(String title, String content, boolean websocket) {
		String bootstrap = "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css";
		return String.format("<!DOCTYPE html><title>%s</title><link href=\"%s\" rel=\"stylesheet\">" +
			"<script type=\"text/javascript\" src=\"https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.1.1/sockjs.min.js\"></script>" +
			"<script type=\"text/javascript\" src=\"https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js\"></script>" +
			"<nav class=\"navbar navbar-inverse navbar-fixed-top\">" +
			"      <div class=\"container\">" +
			"        <div class=\"navbar-header\">" +
			"          <button type=\"button\" class=\"navbar-toggle collapsed\" data-toggle=\"collapse\" data-target=\"#navbar\">" +
			"            <span class=\"sr-only\">Toggle navigation</span>" +
			"            <span class=\"icon-bar\"></span>" +
			"            <span class=\"icon-bar\"></span>" +
			"            <span class=\"icon-bar\"></span>" +
			"          </button>" +
			"          <a class=\"navbar-brand\" href=\"#\"><b>Cat</b>astrophic</a>" +
			"        </div>" +
			"        <div id=\"navbar\" class=\"collapse navbar-collapse\">" +
			"          <ul class=\"nav navbar-nav\">" +
			"            <li class=\"active\"><a href=\"/\">Home</a></li>" +
			"            <li><a href=\"/new\">New cat</a></li>" +
			"            <li><a href=\"https://twitter.com/algrison\">@algrison</a></li>" +
			"          </ul>" +
			"        </div>" +
			"      </div>" +
			"</nav>" +
			"<div class=\"container\" style=\"margin-top: 80px\">" +
			"      <div class=\"starter-template\">" +
			"        <p class=\"lead\">%s</p>" +
			"      </div>" +
			"%s\n" +
			(websocket ? ("<hr/>" +
				"<h2>WebSocket</h2>" +
				"<div class=\"row\">\n" +
				"        <button id=\"connect\" class=\"btn btn-success\" onclick=\"connect();\">Connect</button>\n" +
				"        <button id=\"disconnect\" class=\"btn btn-danger\" disabled=\"disabled\" onclick=\"disconnect();\">Disconnect</button><br/><br/>\n" +
				"        <button id=\"sendNum\" class=\"btn btn-info\" onclick=\"sendList();\">List cats</button>" +
				"    </div>" +
				"<br/><br/><br/><pre id=\"ws\"></pre>" +
				"<script type=\"text/javascript\">\n" +
				"        var stompClient = null; \n" +
				"        function setConnected(connected) {\n" +
				"            document.getElementById('connect').disabled = connected;\n" +
				"            document.getElementById('disconnect').disabled = !connected;\n" +
				"            document.getElementById('ws').innerHTML = '';\n" +
				"        }\n" +
				"        function connect() {\n" +
				"            var socket = new SockJS('/list');\n" +
				"            stompClient = Stomp.over(socket);\n" +
				"            stompClient.connect({}, function(frame) {\n" +
				"                setConnected(true);\n" +
				"                showResult('Connected! - ' + frame);" +
				"                stompClient.subscribe('/topic/cats', function(result){\n" +
				"                    showResult(result.body);\n" +
				"                });\n" +
				"            });\n" +
				"        }\n" +
				"        function disconnect() {\n" +
				"            stompClient.disconnect();\n" +
				"            setConnected(false);\n" +
				"            console.log(\"Disconnected\");\n" +
				"        }\n" +
				"        function sendList() {\n" +
				"            stompClient.send(\"/cats/list\", {}, JSON.stringify({'hello': 'world'}));\n" +
				"        }\n" +
				"        function showResult(message) {\n" +
				"            var response = document.getElementById('ws');\n" +
				"            var p = document.createElement('p');\n" +
				"            p.style.wordWrap = 'break-word';\n" +
				"            p.appendChild(document.createTextNode(message));\n" +
				"            response.appendChild(p);\n" +
				"        }\n" +
				"    </script>") : ""), //
			title, bootstrap, title, content);
	}

	// ---------- PostContruct/PreDestroy ----------
	@PostConstruct
	private void prepare() {
		System.out.println("Hello");
	}

	@PreDestroy
	private void release() {
		System.out.println("Bye");
	}

	// ---------- WebSocket ----------
	@Override
	public void configureMessageBroker(MessageBrokerRegistry config) {
		config.enableSimpleBroker("/topic");
		config.setApplicationDestinationPrefixes("/cats");
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/list").withSockJS();
	}

	@MessageMapping("/list")
	@SendTo("/topic/cats")
	public Iterable<Cat> wsList() throws Exception {
		return listCats();
	}

	// ---------- Main entry point ----------
	public static void main(String[] args) {
		SpringApplication.run(Cat.class, args); // run the whole stuff
	}

	// ---------- Testing ----------
	@Test
	public void testInsert() {
		Cat cat = addCat("foo");
		Assert.assertThat(repository.findOne(cat.id), CoreMatchers.notNullValue());
	}
}
