package gov.nysenate.openleg.service.entity.member.data;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.entity.member.data.MemberDao;
import gov.nysenate.openleg.model.cache.CacheEvictEvent;
import gov.nysenate.openleg.model.cache.CacheEvictIdEvent;
import gov.nysenate.openleg.model.cache.CacheWarmEvent;
import gov.nysenate.openleg.model.cache.ContentCache;
import gov.nysenate.openleg.model.entity.SessionMember;
import gov.nysenate.openleg.service.base.data.CachingService;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Arrays;
import java.util.List;

@Component
public class SessionMemberIdCache implements CachingService<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(SessionMemberIdCache.class);

    private EventBus eventBus;

    private Cache memberCache;

    private CacheManager cacheManager;

    private MemberDao memberDao;

    @Autowired
    public SessionMemberIdCache(EventBus eventBus, MemberDao memberDao, CacheManager cacheManager) {
        this.eventBus = eventBus;
        this.memberDao = memberDao;
        this.cacheManager = cacheManager;
    }

    @PostConstruct
    private void init() {
        eventBus.register(this);
        setupCaches();
        warmCaches();
    }

    @PreDestroy
    private void cleanUp() {
        evictCaches();
        cacheManager.removeCache(ContentCache.SESSION_MEMBER.name());
    }

    public void setupCaches() {
        this.memberCache = new Cache(new CacheConfiguration().name(ContentCache.SESSION_MEMBER.name()).eternal(true));
        cacheManager.addCache(this.memberCache);
    }

    public List<Ehcache> getCaches() {
            return Arrays.asList(memberCache);
    }

    public void evictContent(Integer sessionMemberId) {
        memberCache.remove(sessionMemberId);
    }

    @Override
    @Subscribe
    public void handleCacheEvictEvent(CacheEvictEvent evictEvent) {
        if (evictEvent.affects(ContentCache.SESSION_MEMBER)) {
            evictCaches();
        }
    }

    @Subscribe
    @Override
    public void handleCacheEvictIdEvent(CacheEvictIdEvent<Integer> evictIdEvent) {
        if (evictIdEvent.affects(ContentCache.SESSION_MEMBER)) {
            evictContent(evictIdEvent.getContentId());
        }
    }

    @Override
    public void warmCaches() {
        evictCaches();
        logger.info("Warming up Session member cache");
        memberDao.getAllMembers(SortOrder.ASC, LimitOffset.ALL).stream().forEach(this::putMemberInCache);
        logger.info("Done warming up Session member cache");
    }

    @Override
    @Subscribe
    public void handleCacheWarmEvent(CacheWarmEvent warmEvent) {
        if (warmEvent.affects(ContentCache.SESSION_MEMBER)) {
            warmCaches();
        }
    }

    public boolean isKeyInCache(SimpleKey key) {
        return memberCache.isKeyInCache(key);
    }

    public void putMemberInCache(SessionMember member) {
        memberCache.put(new Element(new SimpleKey(member.getSessionMemberId()), member, true));
    }

    public Cache getCache() {
        return memberCache;
    }

}
