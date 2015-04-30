package kr.co.inogard.springboot.dc.service.schedule;

import java.text.SimpleDateFormat;
import java.util.Date;

import kr.co.inogard.springboot.dc.service.RequestSFROA0802Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

@Component
public class RequestSFROA0802Schedule {
	
	private static final Logger log = LoggerFactory.getLogger(RequestSFROA0802Schedule.class);

	@Autowired
	private RequestSFROA0802Service requestSFROA0802Service;
	
//	@Scheduled(fixedRate = 60000)
	public void run() throws Exception{
		
		StopWatch stopWatch = new StopWatch();
		stopWatch.start("RequestSFROA0802Schedule Run");
		
		String sDate = new SimpleDateFormat("yyyyMMdd").format(new Date());
		String eDate = new SimpleDateFormat("yyyyMMdd").format(new Date());
		
		sDate = "20150401";	// TODO : �׽�Ʈ �� ����
		eDate = "20150420"; // TODO : �׽�Ʈ �� ����
		String orderCode = "�ѱ����װ���";
		
		requestSFROA0802Service.run(sDate, eDate, orderCode);
		
		stopWatch.stop();
		log.debug(stopWatch.getLastTaskTimeMillis() + " ms");
		log.debug(stopWatch.prettyPrint());
	}
	
}