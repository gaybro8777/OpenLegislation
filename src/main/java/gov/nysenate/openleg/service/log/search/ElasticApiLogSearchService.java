package gov.nysenate.openleg.service.log.search;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import gov.nysenate.openleg.client.view.log.ApiLogItemView;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SearchIndex;
import gov.nysenate.openleg.dao.log.search.ElasticApiLogSearchDao;
import gov.nysenate.openleg.model.auth.ApiResponse;
import gov.nysenate.openleg.model.search.ClearIndexEvent;
import gov.nysenate.openleg.model.search.RebuildIndexEvent;
import gov.nysenate.openleg.model.search.SearchException;
import gov.nysenate.openleg.model.search.SearchResults;
import gov.nysenate.openleg.service.base.search.ElasticSearchServiceUtils;
import gov.nysenate.openleg.service.log.event.ApiLogEvent;
import gov.nysenate.openleg.util.AsyncUtils;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@Service
public class ElasticApiLogSearchService implements ApiLogSearchService
{
    private static final Logger logger = LoggerFactory.getLogger(ElasticApiLogSearchService.class);

    @Autowired private EventBus eventBus;
    @Autowired private ElasticApiLogSearchDao apiLogSearchDao;
    @Autowired private AsyncUtils asyncUtils;

    private BlockingQueue<ApiResponse> indexQueue = new ArrayBlockingQueue<>(50000);


    @PostConstruct
    public void init() {
        this.eventBus.register(this);
    }

    @Override
    public SearchResults<ApiLogItemView> searchApiLogs(String query, String sort, LimitOffset limOff) throws SearchException {
        try {
            return apiLogSearchDao.searchLogsAndFetchData(QueryBuilders.queryStringQuery(query), null,
                    ElasticSearchServiceUtils.extractSortBuilders(sort), limOff);
        }
        catch (SearchParseException ex) {
            throw new SearchException("Invalid query string", ex);
        }
        catch (ElasticsearchException ex) {
            throw new SearchException(ex.getMessage(), ex);
        }
    }

    @Override
    public void updateIndex(ApiResponse apiResponse) {
        apiLogSearchDao.updateLogIndex(apiResponse);
    }

    @Override
    public void updateIndex(Collection<ApiResponse> apiResponses) {
        apiLogSearchDao.updateLogIndex(apiResponses);
    }

    @Override
    public void clearIndex() {
        apiLogSearchDao.purgeIndices();
        apiLogSearchDao.createIndices();
    }

    @Override
    public void rebuildIndex() {
        throw new IllegalStateException("Cannot rebuild log search index.");
    }

    @Scheduled(cron = "${scheduler.log.index:* * * * * *}")
    public void indexQueuedLogs() {
        List<ApiResponse> responses = new ArrayList<>(1000);
        indexQueue.drainTo(responses);
        if (responses.size() > 1000) {
            logger.warn("More than 1000 requests queued for indexing: ({})", responses.size());
        }
        updateIndex(responses);
    }

    /**
     * The log event is handled here by putting the response in a queue to be indexed.
     * @param apiLogEvent ApiLogEvent
     */
    @Subscribe
    public void handleApiLogEvent(ApiLogEvent apiLogEvent) {
        try {
            this.indexQueue.put(apiLogEvent.getApiResponse());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    @Subscribe
    public void handleRebuildEvent(RebuildIndexEvent event) {
        if (event.affects(SearchIndex.API_LOG)) {
            rebuildIndex();
        }
    }

    @Override
    @Subscribe
    public void handleClearEvent(ClearIndexEvent event) {
        if (event.affects(SearchIndex.API_LOG)) {
            clearIndex();
        }
    }
}