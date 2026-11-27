package com.workforce.backend.imports.controller;

import com.workforce.backend.auth.CurrentUser;
import com.workforce.backend.auth.Role;
import com.workforce.backend.common.BadRequestException;
import com.workforce.backend.common.ConflictException;
import com.workforce.backend.common.repository.JpaQueryRepository;
import com.workforce.backend.imports.service.ImportRowService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final JpaQueryRepository queries;
    private final ImportRowService importRowService;
    private final ExecutorService pool=Executors.newFixedThreadPool(4);
    public ImportController(JpaQueryRepository queries, ImportRowService importRowService){
        this.queries=queries;
        this.importRowService=importRowService;
    }

    @PostMapping
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if(CurrentUser.details().getRole()!=Role.HR_ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("HR Administrator role required");
        String name=file.getOriginalFilename()==null?"":file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if(!name.endsWith(".csv")) throw new BadRequestException("Only CSV files are supported");
        if(file.getSize()>5*1024*1024) throw new BadRequestException("CSV file exceeds the 5 MB limit");
        Long id=queries.queryForObject("INSERT INTO import_jobs(file_name,status,total_rows,success_rows,failed_rows,created_by) VALUES(?, 'QUEUED',0,0,0,?) RETURNING id",
            Long.class,file.getOriginalFilename(),CurrentUser.details().getEmployeeId());
        File temp=Files.createTempFile("workforce-import-"+id+"-",".csv").toFile();
        file.transferTo(temp);
        pool.execute(()->process(id,temp));
        return queries.queryForMap("SELECT * FROM import_jobs WHERE id=?",id);
    }

    @GetMapping
    public List<Map<String,Object>> list(){
        requireHr();
        return queries.queryForList("SELECT * FROM import_jobs ORDER BY created_at DESC");
    }

    @GetMapping("/{id}")
    public Map<String,Object> get(@PathVariable Long id){
        requireHr();
        return queries.queryForMap("SELECT * FROM import_jobs WHERE id=?",id);
    }

    @GetMapping("/{id}/errors")
    public List<Map<String,Object>> errors(@PathVariable Long id){
        requireHr();
        return queries.queryForList("SELECT row_number,raw_data,error_message FROM import_errors WHERE job_id=? ORDER BY row_number",id);
    }

    private void process(Long jobId,File temp){
        queries.update("UPDATE import_jobs SET status='PROCESSING',started_at=now() WHERE id=?",jobId);
        AtomicInteger success=new AtomicInteger(), failed=new AtomicInteger(), total=new AtomicInteger();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(temp),StandardCharsets.UTF_8))){
            String header=reader.readLine();
            if(header==null) throw new IllegalArgumentException("CSV is empty");
            String[] columns=parseCsv(header);
            Map<String,Integer> index=indexColumns(columns);
            required(index,"employee_id","first_name","last_name","email","date_of_joining","department_code","employment_status");
            ExecutorService rows=Executors.newFixedThreadPool(4);
            List<Future<?>> futures=new ArrayList<>();
            String line;
            while((line=reader.readLine())!=null){
                int rowNumber=total.incrementAndGet()+1;
                String raw=line;
                futures.add(rows.submit(()->importRow(jobId,rowNumber,raw,index,success,failed)));
                if(futures.size()>=100){waitBatch(futures);futures.clear();}
            }
            waitBatch(futures); rows.shutdown();
        }catch(Exception e){
            queries.update("UPDATE import_jobs SET status='FAILED',finished_at=now(),total_rows=?,success_rows=?,failed_rows=? WHERE id=?",
                total.get(),success.get(),failed.get()+1,jobId);
            temp.delete();
            return;
        }
        String status=failed.get()>0?"COMPLETED_WITH_ERRORS":"COMPLETED";
        queries.update("UPDATE import_jobs SET status=?,finished_at=now(),total_rows=?,success_rows=?,failed_rows=? WHERE id=?",
            status,total.get(),success.get(),failed.get(),jobId);
        temp.delete();
    }

    protected void importRow(Long jobId,int rowNumber,String raw,Map<String,Integer> index,
                             AtomicInteger success,AtomicInteger failed){
        try {
            importRowService.processRow(raw,index);
            success.incrementAndGet();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            queries.update("INSERT INTO import_errors(job_id,row_number,raw_data,error_message) VALUES(?,?,?,?)",
                jobId,rowNumber,raw,e.getMessage()==null?"Invalid row":e.getMessage());
        }
    }

    private void waitBatch(List<Future<?>> futures){
        for(Future<?> f:futures){
            try { f.get(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Import processing was interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Import row worker failed", e.getCause());
            }
        }
    }
    private Map<String,Integer> indexColumns(String[] columns){
        Map<String,Integer> m=new HashMap<>();
        for(int i=0;i<columns.length;i++)m.put(columns[i].trim().toLowerCase(),i);
        return m;
    }
    private void required(Map<String,Integer> m,String... names){
        for(String n:names)if(!m.containsKey(n))throw new BadRequestException("Missing required CSV column: "+n);
    }
    private String value(String[] v,Map<String,Integer> i,String key){
        Integer p=i.get(key);return p==null||p>=v.length?"":v[p].trim();
    }
    private String[] parseCsv(String line){
        List<String> values=new ArrayList<>();StringBuilder b=new StringBuilder();boolean quote=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);
            if(c=='"')quote=!quote;
            else if(c==','&&!quote){values.add(b.toString());b.setLength(0);}
            else b.append(c);
        }
        values.add(b.toString());return values.toArray(String[]::new);
    }
    private void requireHr(){
        if(CurrentUser.details().getRole()!=Role.HR_ADMIN)
            throw new org.springframework.security.access.AccessDeniedException("HR Administrator role required");
    }
}
