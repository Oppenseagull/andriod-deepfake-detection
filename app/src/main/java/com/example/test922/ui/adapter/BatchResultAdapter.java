package com.example.test922.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.test922.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量检测结果列表适配器
 */
public class BatchResultAdapter extends RecyclerView.Adapter<BatchResultAdapter.ViewHolder> {

    private final List<BatchResultItem> items = new ArrayList<>();

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_batch_result, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BatchResultItem item = items.get(position);
        holder.tvFileName.setText(item.getFileName());
        holder.tvResult.setText(item.getResultText());
        holder.tvResult.setTextColor(item.getResultColor());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * 清空并设置新数据
     */
    @SuppressWarnings("NotifyDataSetChanged")
    public void setItems(List<BatchResultItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    /**
     * 更新单个项目
     */
    public void updateItem(int position) {
        if (position >= 0 && position < items.size()) {
            notifyItemChanged(position);
        }
    }

    /**
     * ViewHolder 内部类
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName;
        TextView tvResult;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvResult = itemView.findViewById(R.id.tv_result);
        }
    }
}
