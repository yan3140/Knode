package com.knode.content.counter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CounterLuaFakeTest {
    static final String PROGRAM="""
            import sys
            from pathlib import Path
            sys.path.insert(0,sys.argv[1])
            import fakeredis
            scripts=Path(sys.argv[2])
            inc=(scripts/'increment.lua').read_text()
            claim=(scripts/'claim.lua').read_text()
            finish=(scripts/'finish.lua').read_text()
            def client(allow=True):
                r=fakeredis.FakeRedis(decode_responses=True)
                result='true' if allow else 'false'
                r.eval('redis.acl_check_cmd=function(...) return '+result+" end; return 'OK'",0)
                return r
            def hit(r,id='1',base='0',ip='ip',article='article',visitor='visitor',visible='1'):
                return r.eval(inc,5,'counts','dirty',ip,article,visitor,id,base,visible)

            r=client()
            assert hit(r,'9007199254740993','9007199254740993')=='OK'
            assert r.hget('counts','9007199254740993')=='9007199254740994'
            assert hit(r,'9007199254740993','9007199254740993')=='DUP'
            assert r.pttl('visitor')>1790000
            first=r.eval(claim,2,'counts','dirty','1').split(';')[1].split(',')
            assert hit(r,'9007199254740993','9007199254740993',visitor='other')=='OK'
            assert r.eval(finish,2,'counts','dirty',*first)=='AGAIN'
            second=r.eval(claim,2,'counts','dirty','1').split(';')[1].split(',')
            assert second[1]=='9007199254740995'
            assert r.eval(finish,2,'counts','dirty',*second)=='DONE'
            assert r.zcard('dirty')==0

            r=client()
            for n in range(20): assert hit(r)==('OK' if n==0 else 'DUP')
            assert hit(r).startswith('L:') and r.hget('counts','1')=='1'

            r=client()
            for group in range(15):
                for n in range(20):
                    result=hit(r,str(group+1),'0','global','article'+str(group),'visitor'+str(group))
                    assert result==('OK' if n==0 else 'DUP')
            assert hit(r,'99','0','global','new-article','new-visitor').startswith('L:')

            r=client()
            r.hset('counts','1','9223372036854775807')
            before=(r.hgetall('counts'),r.dbsize(),r.zcard('dirty'))
            assert hit(r)=='ERR' and before==(r.hgetall('counts'),r.dbsize(),r.zcard('dirty'))
            r=client()
            r.set('dirty','wrong-type')
            assert hit(r)=='ERR' and r.get('dirty')=='wrong-type' and r.dbsize()==1
            r=client()
            r.zadd('dirty',{'1':1})
            assert hit(r)=='ERR' and not r.exists('counts','ip','article','visitor')
            r=client()
            r.hset('counts','1','2');r.zadd('dirty',{'1':1})
            assert hit(r,'1','3')=='ERR' and r.hget('counts','1')=='2' and not r.exists('ip','article','visitor')
            r=client()
            r.set('ip','1')
            assert hit(r)=='ERR' and not r.exists('counts','dirty','article','visitor')
            r=fakeredis.FakeRedis(decode_responses=True)
            assert hit(r)=='ERR' and r.dbsize()==0
            r=client(False)
            assert hit(r)=='ERR' and r.dbsize()==0
            for denied in ('HSET','SET','ZADD'):
                r=client()
                r.eval("redis.acl_check_cmd=function(cmd,...) return cmd~='"+denied+"' end; return 'OK'",0)
                assert hit(r)=='ERR' and r.dbsize()==0
            r=client()
            r.set('ip','1',px=60000);r.set('article','1',px=60000)
            r.eval("redis.acl_check_cmd=function(cmd,...) return cmd~='INCR' end; return 'OK'",0)
            assert hit(r)=='ERR' and r.get('ip')=='1' and r.get('article')=='1'
            assert not r.exists('counts','dirty','visitor')
            for key in ('counts','dirty','ip','article','visitor'):
                r=client()
                if key in ('counts','dirty'): r.set(key,'wrong')
                else: r.hset(key,'wrong','type')
                assert hit(r)=='ERR' and r.dbsize()==1

            r=client()
            r.hset('counts','1','3');r.zadd('dirty',{'bad':0,'1':1})
            assert r.eval(claim,2,'counts','dirty','1').startswith('1;')
            assert r.eval(claim,2,'counts','dirty','1').startswith('0;1,3,')
            print('counter Lua fake checks passed')
            """;
    @Test void exactScriptsPreflightBoundsAclAndFairRecovery() throws Exception {
        Path deps=Path.of("node_modules","counter-fake-python").toAbsolutePath();
        if(!Files.isDirectory(deps.resolve("fakeredis"))) deps=Path.of("knode-content","node_modules","counter-fake-python").toAbsolutePath();
        Assumptions.assumeTrue(Files.isDirectory(deps.resolve("fakeredis")),"Install isolated fakeredis[lua] fixture");
        Path scripts=Path.of("src","main","resources","counter").toAbsolutePath();
        if(!Files.isDirectory(scripts)) scripts=Path.of("knode-content","src","main","resources","counter").toAbsolutePath();
        String encoded=Base64.getEncoder().encodeToString(PROGRAM.getBytes(StandardCharsets.UTF_8));
        String launcher="import base64;exec(base64.b64decode('"+encoded+"'))";
        Process process=new ProcessBuilder("python","-X","utf8","-c",launcher,deps.toString(),scripts.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS),"fakeredis check timed out");
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),output);
        assertTrue(output.contains("counter Lua fake checks passed"),output);
    }
}
