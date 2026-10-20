@RestController
public class SecurityConfigSafe {
  @CrossOrigin("https://trusted.example")
  @GetMapping("/y")
  public String y() { return "y"; }

  void configure(HttpSecurity http) throws Exception {
    http.csrf().and()
        .authorizeHttpRequests(a -> a.anyRequest().authenticated());
  }
}
