package kr.co.inogard.springboot.dc.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;

import kr.co.inogard.springboot.dc.domain.OpenAPIRequest;
import kr.co.inogard.springboot.dc.domain.RequestSFROA0802;
import kr.co.inogard.springboot.dc.domain.RequestSFROA0802Domain;
import kr.co.inogard.springboot.dc.domain.RequestSFROA0802DomainKey;
import kr.co.inogard.springboot.dc.domain.Response;
import kr.co.inogard.springboot.dc.domain.ResponseFileDomain;
import kr.co.inogard.springboot.dc.domain.ResponseSFROA0802;
import kr.co.inogard.springboot.dc.domain.ResponseSFROA0802Domain;
import kr.co.inogard.springboot.dc.external.domain.ExternalResponseSFROA0802Domain;
import kr.co.inogard.springboot.dc.repository.RequestSFROA0802Repository;
import kr.co.inogard.springboot.dc.utils.FileUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.converter.DefaultJobParametersConverter;
import org.springframework.batch.core.converter.JobParametersConverter;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;

@Service
public class RequestSFROA0802Service {
	
	private static final Logger log = LoggerFactory.getLogger(RequestSFROA0802Service.class);
	
	@Value("${agent.root}")
	private String agentRootDirectory;
	
	@Autowired
	private OpenAPIRequestService openAPIRequestService;
	
	@Autowired
	private AnnStdDocAsyncDownloadService annStdDocAsyncDownloadService;
	
	@Autowired
	private RequestSFROA0802Repository requestSFROA0802Repository;
	
	@Autowired
	private JpaRepository responseSFROA0802Repository;
	
	@Autowired
	private JpaRepository responseFileRepository;
	
	@Autowired
	@Qualifier("datasourceOneTransactionManager")
	private PlatformTransactionManager datasourceOneTransactionManager;
	
	@Autowired
	@Qualifier("datasourceTwoTransactionManager")
	private PlatformTransactionManager datasourceTwoTransactionManager;
	
	@Autowired
	@Qualifier("datasourceOneEntityManager")
	private EntityManagerFactory datasourceOneEntityManager;
	
	@Autowired
	@Qualifier("datasourceOneDataSource")
	private DataSource dataSource;
	
	@Autowired
	private JobBuilderFactory jobBuilderFactory;
 
	@Autowired
	private StepBuilderFactory stepBuilderFactory;
	
	@Autowired
	@Qualifier("responseSFROA0802ItemReader")
	private ResponseSFROA0802ItemReader responseSFROA0802ItemReader;
	
	@Autowired
	private ResponseSFROA0802ItemWriterListener responseSFROA0802ItemWriterListener;

	@Autowired
	private ResponseSFROA0802JobExecutionListener responseSFROA0802JobExecutionListener;
	
	@Autowired
	@Qualifier("datasourceTwoEntityManager")
	private EntityManagerFactory datasourceTwoEntityManager;
	
	public void run(String sDate, String eDate, String orderCode) throws Exception{

		Assert.notNull(sDate, "'sDate' must not be null");
		Assert.notNull(eDate, "'eDate' must not be null");
		Assert.notNull(orderCode, "'orderCode' must not be null");
		
		// 업무 트렌젝션(DB아님) 단위의 키로 사용할 값을 생성하여 OpenAPIContext에 담아서 같은 값을 사용할 수 있도록 함.
		String groupId	= new SimpleDateFormat("yyyyMMddhhmmss").format(new Date());
		int requestSeq 	= 1;
		int pageSize 	= 200;
		int pageNo 		= 1;
		
		OpenAPIRequest openAPIRequest = new OpenAPIRequest();
		openAPIRequest.setGroupId(groupId);
		openAPIRequest.setRequestSeq(requestSeq);
		OpenAPIContext.set(openAPIRequest);
		
		String subUrl = "BidPublicInfoService/getInsttAcctoBidPblancListThng";
		
		RequestSFROA0802 request = new RequestSFROA0802();
		request.setGroupId(groupId);
		request.setRequestSeq(requestSeq);
		request.setNumOfRows(pageSize);
		request.setPageNo(pageNo);
		request.setSDate(sDate);
		request.setEDate(eDate);
		request.setOrderCode(orderCode);
		
		List<ResponseSFROA0802> listResponse = new ArrayList();
		
		Response response = this.getDataFromOpenAPI(subUrl, request, listResponse);
		System.out.println("TotalResultCount : " + listResponse.size());
		
		List<ResponseFileDomain> listDownloadFileCandidate = new ArrayList<>();
		for(ResponseSFROA0802 responseSFROA0802 : listResponse){
			System.out.println(responseSFROA0802);
			
			ResponseSFROA0802Domain responseSFROA0802Domain = new ResponseSFROA0802Domain();
			BeanUtils.copyProperties(responseSFROA0802, responseSFROA0802Domain);
			
			responseSFROA0802Domain.setTransferYn("N");
			responseSFROA0802Repository.save(responseSFROA0802Domain);
			if(null != responseSFROA0802.getAnnStdDoc1()
					&& !"".equals(responseSFROA0802.getAnnStdDoc1())){
				
				ResponseFileDomain responseFileDomain = new ResponseFileDomain();
				responseFileDomain.setUrl(responseSFROA0802.getAnnStdDoc1());
				
				responseFileRepository.save(responseFileDomain);
				
				listDownloadFileCandidate.add(responseFileDomain);
			}
		}
		responseFileRepository.flush();
		responseSFROA0802Repository.flush();
		
		if(listDownloadFileCandidate.size() > 0){
			try {
				System.out.println("#############################");
				System.out.println("비동기 호출 시작");
				System.out.println("#############################");
				
				for(ResponseFileDomain responseFileDomain : listDownloadFileCandidate){
					Future<ResponseFileDomain> future = annStdDocAsyncDownloadService.download(responseFileDomain);
					if(future.isDone()){
						System.out.println("######## 비동기 작업 완료 ########");
					}
				}
				System.out.println("#############################");
				System.out.println("비동기 호출 끝");
				System.out.println("#############################");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		if(listResponse.size() > 0){
			Properties prop = new Properties();
			prop.setProperty("groupId", groupId);
			prop.setProperty("requestSeq", Integer.toString(requestSeq));
			prop.setProperty("pageSize", Integer.toString(pageSize));
			prop.setProperty("pageNo", Integer.toString(pageNo));
			prop.setProperty("sDate", sDate);
			prop.setProperty("eDate", eDate);
			prop.setProperty("orderCode", orderCode);
			
			JobParametersConverter jobParametersConverter = new DefaultJobParametersConverter();
			JobParameters jobParameters = jobParametersConverter.getJobParameters(prop);
			
			JobRepositoryFactoryBean jobRepositoryFactoryBean = new JobRepositoryFactoryBean();
			jobRepositoryFactoryBean.setTransactionManager(datasourceOneTransactionManager);
			jobRepositoryFactoryBean.setDataSource(dataSource);
			
			SimpleAsyncTaskExecutor simpleAsyncTaskExecutor = getSimpleAsyncTaskExecutor();
			
			SimpleJobLauncher simpleJobLauncher = new SimpleJobLauncher();
	        simpleJobLauncher.setJobRepository(jobRepositoryFactoryBean.getObject());
	        simpleJobLauncher.setTaskExecutor(simpleAsyncTaskExecutor);
	        simpleJobLauncher.afterPropertiesSet();
	        
	        JobExecution execution = simpleJobLauncher.run(job(), jobParameters);
	        System.out.println("execution.getId() = " + execution.getId());
	        System.out.println("Exit Status : " + execution.getStatus());
	        
	        
			openAPIRequest = OpenAPIContext.get();
			RequestSFROA0802DomainKey id = new RequestSFROA0802DomainKey();
			id.setGroupId(openAPIRequest.getGroupId());
			id.setRequestSeq(openAPIRequest.getRequestSeq());
			
			RequestSFROA0802Domain requestSFROA0802Domain = requestSFROA0802Repository.findOne(id);
	        requestSFROA0802Domain.setJobExecutionId(execution.getId());
	        requestSFROA0802Domain.setJobExecutionStatus(execution.getStatus().toString());
	        requestSFROA0802Repository.saveAndFlush(requestSFROA0802Domain);
		}
		
		// 사용이 끝나면 삭제하기
		OpenAPIContext.reset();
		
		Thread.sleep(15000);
		
		System.out.println("#############################");
		System.out.println("업무 끝");
		System.out.println("#############################");	
	}
	
	public Response getDataFromOpenAPI(String subUrl, RequestSFROA0802 request, List<ResponseSFROA0802> listResponse) throws Exception{
		
		RequestSFROA0802Domain requestSFROA0802Domain = new RequestSFROA0802Domain();
		BeanUtils.copyProperties(request, requestSFROA0802Domain);
		
		// 조회조건 저장
		System.out.println("RequestSFROA0802Domain.getGroupId() = " + requestSFROA0802Domain.getGroupId());
		System.out.println("RequestSFROA0802Domain.getRequestSeq() = " + requestSFROA0802Domain.getRequestSeq());
		System.out.println("RequestSFROA0802Domain.getOrderCode() = " + requestSFROA0802Domain.getOrderCode());
		requestSFROA0802Repository.save(requestSFROA0802Domain);
		
		// 조회
		Response response = openAPIRequestService.request(subUrl, request);
		System.out.println("ResultCode = "	+ response.getHeader().getResultCode());
		System.out.println("ResultMsg = " 	+ response.getHeader().getResultMsg());
		System.out.println("NumOfRows = " 	+ response.getBody().getNumOfRows());
		System.out.println("PageNo = " 		+ response.getBody().getPageNo());
		System.out.println("TotalCount = " 	+ response.getBody().getTotalCount());
		
		// 조회조건 결과 저장
		requestSFROA0802Domain.setResultCode(response.getHeader().getResultCode());
		requestSFROA0802Domain.setResultMsg(response.getHeader().getResultMsg());
		requestSFROA0802Domain.setTotalCount(response.getBody().getTotalCount());
		requestSFROA0802Domain.setHashCode(FileUtil.getHashSHA256FromFilepath(agentRootDirectory+request.getGroupId()+"_"+request.getRequestSeq()+".xml"));	// Response 객채를 Hash로 변환해서 저장한다.
		requestSFROA0802Repository.saveAndFlush(requestSFROA0802Domain);
		
		if(response.getBody().getTotalCount() > 0 
				&& null != response.getBody().getItems()
				&& response.getBody().getItems().getItem().size() > 0){
			
			boolean bolWork = true;
			if(request.getRequestSeq() == 1){
				RequestSFROA0802Domain existRequestSFROA0802Domain = new RequestSFROA0802Domain();
				List<RequestSFROA0802Domain> listRequestSFROA0802Domain = requestSFROA0802Repository.findBySDateAndEDateAndOrderCode(request.getGroupId(), request.getSDate(), request.getEDate(), request.getOrderCode());
				if(null != listRequestSFROA0802Domain && listRequestSFROA0802Domain.size() > 0){
					existRequestSFROA0802Domain = listRequestSFROA0802Domain.get(0);
					if(null == existRequestSFROA0802Domain.getHashCode()){
						existRequestSFROA0802Domain.setHashCode("");
					}
					
					System.out.println("#########################################");
					System.out.println("["+existRequestSFROA0802Domain.getHashCode()+"]equals["+requestSFROA0802Domain.getHashCode()+"]");
					// Hash값이 같다면(똑같은 내용이라면 여러번 작업할 이유가 없으므로)
					if(existRequestSFROA0802Domain.getHashCode().equals(requestSFROA0802Domain.getHashCode())){
						bolWork = false;
					}
					System.out.println(Boolean.toString(bolWork));
					System.out.println("#########################################");
				}
			}
			
			if(bolWork){
				int seq = 1;
				for(Iterator<ResponseSFROA0802> iter = response.getBody().getItems().getItem().iterator(); iter.hasNext();){
					
					ResponseSFROA0802 item = iter.next();
					if(item.getOrderOrgNm().indexOf("한국공항공사") > -1){
						item.setGroupId(request.getGroupId());
						item.setRequestSeq(request.getRequestSeq());
						item.setSeq(seq++);
						listResponse.add(item);
					}
				}
			}
		}
		
		// 페이징
		Paging paging = new Paging();
		paging.setPageSize(response.getBody().getNumOfRows());
		paging.setPageNo(response.getBody().getPageNo());
		paging.setTotalCount(response.getBody().getTotalCount());
		
		// 다음 페이지 내용 가져오기
		if(paging.getNextPageNo() > response.getBody().getPageNo()){
			request.setPageNo(paging.getNextPageNo());
			request.setRequestSeq(paging.getNextPageNo());
			
			OpenAPIRequest openAPIRequest = OpenAPIContext.get();
			openAPIRequest.setRequestSeq(paging.getNextPageNo());
			openAPIRequest.setListResponse(listResponse);
			OpenAPIContext.set(openAPIRequest);
			
			return getDataFromOpenAPI(subUrl, request, listResponse);
		}
		
		return response;
	}
	
	@Bean
	public SimpleAsyncTaskExecutor getSimpleAsyncTaskExecutor(){
		return new SimpleAsyncTaskExecutor();
	}
	
	@Bean
	public Job job() throws Exception {
		return jobBuilderFactory.get("responseSFROA0802ExportToExternal")
	                            .start(step1())
	                            .listener(responseSFROA0802JobExecutionListener)
	                            .build();
	}
	
	@Bean
	public Step step1() throws Exception {
		return ((SimpleStepBuilder<ResponseSFROA0802Domain, ExternalResponseSFROA0802Domain>) stepBuilderFactory.get("responseSFROA0802DomainTransfer")
                .<ResponseSFROA0802Domain, ExternalResponseSFROA0802Domain> chunk(100) // 읽기/쓰기 단위
                .transactionManager(datasourceTwoTransactionManager))
                .reader(responseSFROA0802ItemReader)
                .writer(responseSFROA0802Writer())
                .processor(responseSFROA0802Processor())
                .listener(responseSFROA0802ItemWriterListener)
//                .taskExecutor(responseExecutor)
//                .throttleLimit(2) // 동시실행 쓰레드 갯수
                .build();
	}
	
	@Bean
	public JpaItemWriter<ExternalResponseSFROA0802Domain> responseSFROA0802Writer() {
    	JpaItemWriter writer = new JpaItemWriter();
		writer.setEntityManagerFactory(datasourceTwoEntityManager);
	    return writer;
	}
	
	@Bean
	public ItemProcessor<ResponseSFROA0802Domain, ExternalResponseSFROA0802Domain> responseSFROA0802Processor() {
        return new ResponseSFROA0802ItemProcessor();
    }
}
