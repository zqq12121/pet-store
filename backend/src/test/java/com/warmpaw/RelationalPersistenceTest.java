package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.persistence.RelationalRepository;
import com.warmpaw.tools.MigrateLegacy;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 专门证明实际列读写、附件关系、外键和旧数据迁移，而非仅检查HTTP返回。 */
class RelationalPersistenceTest {
  JdbcTemplate jdbc;RelationalRepository repository;TransactionTemplate tx;SingleConnectionDataSource ds;
  @BeforeEach void setup() throws Exception {
    ds=new SingleConnectionDataSource("jdbc:h2:mem:relational_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","",true);
    jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
    repository=new RelationalRepository(jdbc);
  }
  @AfterEach void close(){ds.destroy();}
  Map<String,Object> entity(String kind,String id) {
    return map("id",kind+"_"+id,"status",kind.equals("pet")?"off":"ready","version",1,"createdAt","2026-09-10T00:00:00Z","updatedAt","2026-09-10T00:00:00Z");
  }
  Map<String,Object> pet() {
    var p=entity("pet","one");p.putAll(map("name","测试猫","category","cat","breed","布偶","gender","female","priceAmount",200000,"ageMonths",8,"weightKg",2.5,"personalityTags",List.of("亲人"),"imageFileIds",List.of("file_one"),"quarantine",map("certificateNo","TEST ONLY","validUntil","2027-01-01","publicImageFileIds",List.of("file_one"),"originalFileIds",List.of())));return p;
  }
  @Test void writesColumnsAndReadsDatabaseChanges() {
    repository.insert("file",null,entity("file","one"));repository.insert("pet",null,pet());
    assertEquals(200000,jdbc.queryForObject("SELECT price_amount FROM pets WHERE id='pet_one'",Long.class));
    assertEquals("file_one",jdbc.queryForObject("SELECT file_id FROM pet_images WHERE parent_id='pet_one'",String.class));
    assertEquals("亲人",jdbc.queryForObject("SELECT tag FROM pet_personality_tags WHERE parent_id='pet_one'",String.class));
    jdbc.update("UPDATE pets SET price_amount=300000 WHERE id='pet_one'");
    assertEquals(300000,number(repository.find("pet","pet_one"),"priceAmount"));
    assertThrows(Exception.class,() -> jdbc.update("INSERT INTO pet_occupancy VALUES('missing','missing')"));
  }
  @Test void failedAttachmentChangeRollsBackParentAndChildren() {
    repository.insert("file",null,entity("file","one"));repository.insert("pet",null,pet());
    assertThrows(Exception.class,() -> tx.executeWithoutResult(s -> {
      var p=repository.find("pet","pet_one");p.put("priceAmount",999);p.put("imageFileIds",List.of("missing_file"));repository.update(p);
    }));
    assertEquals(200000,number(repository.find("pet","pet_one"),"priceAmount"));
    assertEquals(List.of("file_one"),strings(repository.find("pet","pet_one"),"imageFileIds"));
  }
  @Test void migratesEveryLegacyFieldAndCanResume() {
    jdbc.execute("CREATE TABLE resources(kind VARCHAR(32),business_key VARCHAR(190),payload LONGTEXT,created_at VARCHAR(40),id VARCHAR(64))");
    for(var pair:List.of(map("kind","file","data",entity("file","one")),map("kind","pet","data",pet()))) {
      var e=object(pair,"data");jdbc.update("INSERT INTO resources VALUES(?,?,?,?,?)",pair.get("kind"),null,write(e),e.get("createdAt"),e.get("id"));
    }
    MigrateLegacy.migrate(jdbc,repository,tx);
    MigrateLegacy.assertPreserved(pet(),repository.find("pet","pet_one"),"pet");
    assertEquals(2,jdbc.queryForObject("SELECT source_count FROM schema_migrations",Integer.class));
    MigrateLegacy.migrate(jdbc,repository,tx);
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM pets",Integer.class));
  }
  @Test void unmappedFieldsFailBeforeAnyWrite() {
    var p=pet();p.put("unmappedBusinessField","must not disappear");
    assertThrows(IllegalArgumentException.class,() -> repository.insert("pet",null,p));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pets",Integer.class));
  }
}
