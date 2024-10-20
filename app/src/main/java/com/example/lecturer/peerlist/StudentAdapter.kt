package com.example.lecturer.peerlist

import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lecturer.R

class StudentAdapter(private val listener: StudentAdapterInterface) : RecyclerView.Adapter<StudentAdapter.StudentViewHolder>() {

    private val studentList: MutableList<String> = mutableListOf() // Change to hold student IDs or names

    class StudentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val studentIdTextView: TextView = itemView.findViewById(R.id.tvStudentID)
        val askQuestionButton: Button = itemView.findViewById(R.id.btnAskQuestion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StudentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.student_item, parent, false) // Inflate your item layout for students
        return StudentViewHolder(view)
    }

    override fun onBindViewHolder(holder: StudentViewHolder, position: Int) {
        val studentId = studentList[position]

        holder.studentIdTextView.text = studentId // Set the student ID or name to the TextView

        holder.askQuestionButton.setOnClickListener {
            listener.onStudentClicked(studentId) // Trigger the interface function with the clicked student's ID
        }
    }

    override fun getItemCount(): Int {
        return studentList.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(newStudents: Collection<String>) {
        studentList.clear()
        studentList.addAll(newStudents)
        Log.d("StudentAdapter", "Updated list: $studentList")
        notifyDataSetChanged()
    }
}

