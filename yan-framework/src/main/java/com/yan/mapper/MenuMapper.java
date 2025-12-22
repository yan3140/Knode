package com.yan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yan.domain.entity.Menu;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;


/**
 * 菜单权限表(Menu)表数据库访问层
 *
 * @author makejava
 * @since 2025-12-15 15:25:13
 */
@Mapper
public interface MenuMapper extends BaseMapper<Menu> {

    List<String> selectPermById(Long id);
}

