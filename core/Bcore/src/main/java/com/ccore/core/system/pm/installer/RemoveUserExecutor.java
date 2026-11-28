package com.ccore.core.system.pm.installer;

import com.ccore.core.env.BEnvironment;
import com.ccore.core.system.pm.BPackageSettings;
import com.ccore.entity.pm.InstallOption;
import com.ccore.utils.FileUtils;

/**
 * Created by Milk on 4/24/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 * 创建用户相关
 */
public class RemoveUserExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        // delete user dir
        FileUtils.deleteDir(BEnvironment.getDataDir(packageName, userId));
        FileUtils.deleteDir(BEnvironment.getDeDataDir(packageName, userId));
        FileUtils.deleteDir(BEnvironment.getExternalDataDir(packageName, userId));
        return 0;
    }
}
