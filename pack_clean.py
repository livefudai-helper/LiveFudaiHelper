#!/usr/bin/env python3
"""
干净打包脚本 - 只包含必要文件
使用方法：python pack_clean.py
"""

import os
import zipfile

source_dir = r"F:\WorkBuddy\学习\LiveFudaiHelper-Final-v6"
zip_path = r"F:\WorkBuddy\学习\LiveFudaiHelper-v8-clean.zip"

# 要排除的文件/文件夹
exclude = {
    '.git',
    '.gitignore',
    'pack.py',
    'pack.ps1',
    'UPDATE_CONFIG.md',
    'build',
    '.gradle',
}

# 要排除的文件扩展名
exclude_ext = {
    '.iml',
    '.log',
}

# 删除旧文件
if os.path.exists(zip_path):
    os.remove(zip_path)

# 打包
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for root, dirs, files in os.walk(source_dir):
        # 排除目录
        dirs[:] = [d for d in dirs if d not in exclude]
        
        # 添加文件
        for file in files:
            # 排除文件
            if file in exclude:
                continue
            
            # 排除特定扩展名
            if any(file.endswith(ext) for ext in exclude_ext):
                continue
            
            file_path = os.path.join(root, file)
            
            # 计算相对路径
            arcname = os.path.relpath(file_path, source_dir)
            
            # 排除特定路径
            if any(part in exclude for part in arcname.split(os.sep)):
                continue
            
            print(f"添加: {arcname}")
            zipf.write(file_path, arcname)

print(f"\n✅ 打包完成！")
print(f"   Zip 路径: {zip_path}")
print(f"   Zip 大小: {os.path.getsize(zip_path)} 字节")

# 验证
with zipfile.ZipFile(zip_path, 'r') as zipf:
    files = zipf.namelist()
    has_github = any(f.startswith('.github/') for f in files)
    
    if has_github:
        print(f"✅ 包含 .github 文件夹")
    else:
        print(f"⚠️ 警告: .github 文件夹未包含在 zip 中")
    
    print(f"\n📂 Zip 内容（前20项）:")
    for f in files[:20]:
        print(f"   {f}")
    
    if len(files) > 20:
        print(f"   ... 还有 {len(files) - 20} 个文件")
