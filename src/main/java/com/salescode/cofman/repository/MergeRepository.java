package com.salescode.cofman.repository;

import com.salescode.cofman.model.Merge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MergeRepository extends JpaRepository<Merge,String> {

    List<Merge> findByRequester(String requester);
    List<Merge> findByMerger(String merger);


}
